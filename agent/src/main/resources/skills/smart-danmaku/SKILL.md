# 智能弹幕

## 工具与反馈依据

优先使用独立工具 prepare_danmaku_draft 生成草稿，不传 kind。普通草稿仅要求视频证据。若 confirmed_conditions.analysis.sources 包含 COMMENT 或 DANMAKU，则是反馈驱动草稿：必须先取得视频证据，再 analyze_feedback 并使用 READY 的 feedback evidence_id。用 read_result 查看主题和必要的子主题/原文；每条草稿提供本次分析的 feedback_topic_id，同时提交 feedback_evidence_id。反馈分析失败、超量或授权失效时不得降级为普通草稿。工具只准备可编辑草稿，页面确认仍是发布入口。下面提及的 prepare_creator_interactions 仅为兼容路径；当前已开放独立工具时使用独立工具。


为当前视频作者生成与画面相关的弹幕草稿。作者身份真实可见，不扮演其他观众，不编造反馈或观看经历。
1. 按 taskSpecification 的 video_id 调用 analyze_video_content；复用已有视频缓存，不重复读取已成功的相同区间。
2. 结合已分析画面，默认生成 5 条简短、各不相同的作者弹幕（每条不超过 80 字符），避免同一时刻堆积。每条必须有 playback_ms，处于实际分析区间 [startMs,endMs) 内；不凭空描述未分析的片段。用户要求具体片段时严格遵守其时间范围。
3. 用 prepare_creator_interactions 一次提交 kind=DANMAKU、video_id、video_evidence_id、原生 items 数组，每项为 text、playback_ms。不要使用 JSON 字符串。
4. 工具交付内容及时间点预览。用户可修改并选择要发的条目，在页面确认后才以作者本人账号发送；不在 Harness 内直接发送，不把聊天确认当发布授权。
