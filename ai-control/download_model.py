"""从魔塔社区 (modelscope.cn) 下载单个 GGUF 模型文件"""
import os
import sys

save_dir = sys.argv[1] if len(sys.argv) > 1 else "models"
os.makedirs(save_dir, exist_ok=True)

model_id = "Qwen/Qwen2.5-0.5B-Instruct-GGUF"
filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
save_path = os.path.join(save_dir, filename)

# 如果已存在且完整则跳过（约468MB）
if os.path.isfile(save_path):
    size_mb = os.path.getsize(save_path) / 1024 / 1024
    if size_mb > 400:
        print(f"文件已存在: {save_path} ({size_mb:.1f} MB)")
        sys.exit(0)
    else:
        print(f"文件不完整 ({size_mb:.1f} MB)，重新下载")
        os.remove(save_path)

print(f"从魔塔下载: {model_id} -> {filename}")
from modelscope.hub.file_download import model_file_download

local_path = model_file_download(
    model_id=model_id,
    file_path=filename,
    local_dir=save_dir,
)
size_mb = os.path.getsize(local_path) / 1024 / 1024
print(f"下载完成: {local_path} ({size_mb:.1f} MB)")
