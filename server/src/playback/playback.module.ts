import { Module } from "@nestjs/common";
import { PlaybackController } from "./playback.controller";
import { PlaybackRepository } from "./playback.repository";

/** 最近播放、播放会话与账号听歌统计。 */
@Module({ controllers: [PlaybackController], providers: [PlaybackRepository] })
export class PlaybackModule {}
