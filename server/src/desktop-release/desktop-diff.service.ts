import { Injectable, Logger } from "@nestjs/common";
import { execFile } from "node:child_process";
import { existsSync, rmSync, statSync } from "node:fs";
import { extname, relative, resolve } from "node:path";
import { promisify } from "node:util";
import { AppConfigService } from "../config/app-config.service";
import { DesktopArtifactService, type StoredDesktopArtifact } from "./desktop-artifact.service";

const execFileAsync = promisify(execFile);
const importEsm = new Function("specifier", "return import(specifier)") as (
  specifier: string,
) => Promise<{ loadBsdiff: () => Promise<BsdiffRuntime> }>;

type BsdiffRuntime = {
  FS: {
    mkdir(path: string): void;
    mount(type: unknown, options: { root: string }, mountpoint: string): void;
    chdir(path: string): void;
  };
  NODEFS: unknown;
  callMain(args: string[]): number;
};

export type GeneratedDesktopPatch = StoredDesktopArtifact & { algorithm: "bsdiff" | "courgette" };

/** 为相邻桌面版本预计算差分；失败时保留完整文件回退，不阻断发布。 */
@Injectable()
export class DesktopDiffService {
  private readonly logger = new Logger(DesktopDiffService.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly artifacts: DesktopArtifactService,
  ) {}

  async create(oldFileName: string, newFileName: string, logicalPath: string): Promise<GeneratedDesktopPatch | null> {
    const oldFile = this.artifacts.absolutePath(oldFileName);
    const newFile = this.artifacts.absolutePath(newFileName);
    if (!existsSync(oldFile) || !existsSync(newFile)) return null;

    const nativePe = [".dll", ".exe"].includes(extname(logicalPath).toLowerCase());
    if (nativePe && this.config.courgettePath) {
      const generated = await this.tryCourgette(oldFile, newFile);
      if (generated) return generated;
    }
    return this.tryBsdiff(oldFile, newFile);
  }

  private async tryCourgette(oldFile: string, newFile: string): Promise<GeneratedDesktopPatch | null> {
    const temporary = this.artifacts.temporaryPatchFile();
    try {
      await execFileAsync(this.config.courgettePath, ["-gen", oldFile, newFile, temporary], {
        windowsHide: true,
        timeout: 10 * 60_000,
        maxBuffer: 1024 * 1024,
      });
      return await this.commitUseful(temporary, newFile, "courgette");
    } catch (error) {
      rmSync(temporary, { force: true });
      this.logger.warn(`Courgette 差分失败，回退 bsdiff：${this.messageOf(error)}`);
      return null;
    }
  }

  private async tryBsdiff(oldFile: string, newFile: string): Promise<GeneratedDesktopPatch | null> {
    const temporary = this.artifacts.temporaryPatchFile();
    try {
      const { loadBsdiff } = await importEsm("bsdiff-wasm");
      const runtime = await loadBsdiff();
      const root = resolve(this.config.desktopReleaseDirectory);
      runtime.FS.mkdir("/working");
      runtime.FS.mount(runtime.NODEFS, { root }, "/working");
      runtime.FS.chdir("/working");
      const argumentsForRuntime = [oldFile, newFile, temporary].map((file) =>
        relative(root, file).replace(/\\/g, "/"),
      );
      const exitCode = runtime.callMain(argumentsForRuntime);
      if (exitCode !== 0) throw new Error(`bsdiff 退出码 ${exitCode}`);
      return await this.commitUseful(temporary, newFile, "bsdiff");
    } catch (error) {
      rmSync(temporary, { force: true });
      this.logger.warn(`bsdiff 差分失败，将下发完整模块：${this.messageOf(error)}`);
      return null;
    }
  }

  private async commitUseful(
    temporary: string,
    newFile: string,
    algorithm: "bsdiff" | "courgette",
  ): Promise<GeneratedDesktopPatch | null> {
    if (!existsSync(temporary)) throw new Error("差分工具没有生成输出文件");
    // 差分至少节省 10% 才值得保存和下发；否则完整内容寻址对象更简单可靠。
    if (statSync(temporary).size >= statSync(newFile).size * 0.9) {
      rmSync(temporary, { force: true });
      return null;
    }
    const stored = await this.artifacts.commitGeneratedAsync(temporary, "patch");
    return { ...stored, algorithm };
  }

  private messageOf(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
  }
}
