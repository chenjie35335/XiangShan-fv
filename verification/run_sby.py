import os
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from tqdm import tqdm

target_dir = "./sbys"
timeout_sec = 1200
max_workers = 4
timeout_log = "timeout_details.log"

def process_file(filepath):
    try:
        subprocess.run(
            ["sby", filepath, "-f"],
            timeout=timeout_sec,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        return {"status": "success", "file": filepath}
    except TimeoutExpired:
        return {"status": "timeout", "file": filepath}
    except Exception:
        return {"status": "error", "file": filepath}

def batch_process():
    files = [os.path.join(target_dir, f) for f in os.listdir(target_dir) if f.endswith(".sby")]
    timeout_list = []

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(process_file, f): f for f in files}

        for future in tqdm(as_completed(futures), total=len(files), desc="Processing"):
            result = future.result()
            if result["status"] == "timeout":
                timeout_list.append(os.path.basename(result["file"]))

    # 生成超时报告
    with open(timeout_log, "w") as f:
        f.write("超时文件清单：\n" + "\n".join(timeout_list))

    print(f"检测完成！超时文件已保存至 {timeout_log}")

if __name__ == "__main__":
    batch_process()
