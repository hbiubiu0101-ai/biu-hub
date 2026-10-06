# BIU HUB V1.4.1 · Supabase + GitHub Pages

基准：用户指定的 `biu-hub-V1.4.1-D1-fixed`。

仅调整运行架构：
- 保留 V1.4.1 的 UI、功能与工具目录。
- D1 / Worker 数据接口替换为 Supabase REST。
- 可直接部署到 GitHub Pages。
- 修复图表首次渲染早于 ECharts 初始化导致的 `setOption` 报错。
- 手机与电脑读取/写入同一个 `bookkeeping_state` 云端记录。

部署：将压缩包内文件上传到 GitHub 仓库根目录，Pages 选择 `main / (root)`。
