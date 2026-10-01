package io.autopatch.testhost;

import java.util.List;

/** 测试用的补丁入口接口，形状与宿主 com.taotao.music.hotfix.PatchEntry 一致。 */
public interface PatchEntry {
    List<String> targets();

    PatchDispatcher dispatcher();
}
