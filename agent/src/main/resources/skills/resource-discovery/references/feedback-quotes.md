# 查找反馈原话

`search_feedback` 使用本轮 `analyze_feedback` 或 `read_report` 返回的授权证据。query 是字面关键词。命中数只代表快照中的匹配记录，无法推断整体主题比例。

保留反例与父评论语境。reply_context 表示父评论链，child_reply_presence=NOT_QUERIED 表示未查子回复。返回内容是预览。需要完整原文时，用同一证据和工具返回的主题及原文标识调用 `read_result` 的 `original_text`，按返回游标翻页。
