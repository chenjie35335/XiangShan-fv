import re
import os

# 文件路径配置
TEMPLATE_FILE = "check.sby"  # 原始脚本文件名
PARAM_FILE = "test.txt"           # 参数文件名
OUTPUT_PREFIX = "generated_"       # 输出文件前缀

os.mkdir('sbys')

# 读取模板内容
with open(TEMPLATE_FILE, 'r') as f:
    template = f.read()

# 获取所有待替换参数（过滤空行）
with open(PARAM_FILE, 'r') as f:
    modules = [line.strip() for line in f if line.strip()]

# 生成每个配置文件
for module in modules:
    # 保留原有缩进格式，只替换模块名
    new_content = re.sub(
        r'^(\s*prep -top\s+)\S+',
        rf'\g<1>{module}',
        template,
        flags=re.MULTILINE,
        count=1  # 只替换第一个匹配项
    )
    
    # 写入新文件（文件名添加模块后缀）
    output_file = f"sbys/{OUTPUT_PREFIX}{module}.sby"
    with open(output_file, 'w+') as f:
        f.write(new_content)
