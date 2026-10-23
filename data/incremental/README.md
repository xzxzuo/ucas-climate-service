# 已退役的旧增量目录

2026-10-23 已移除扫描这个目录的旧增量服务。这里已有的 CSV/`.part` 文件保留，当前应用不会读取或删除它们。

请在平台创建普通数据集，使用网页上传，或启用 `CLIMATE_PLATFORM_INBOX_ENABLED=true` 后投递到平台私有目录 `inbox/<datasetId>/`。Docker 中是 `/app/.runtime/platform/inbox/<datasetId>/`。

仍需标准化 13 列 CSV，并按 `.part` → `.csv` 原子发布；不要投递 45 GB 原文件。当前的去重、任务与重试语义见项目根 README 和 `docs/platform-guide.md`，不再沿用旧目录的自动退避重试。
