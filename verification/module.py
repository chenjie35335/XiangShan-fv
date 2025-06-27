import re
import sys

def remove_comments(text):
    """移除Verilog单行和多行注释"""
    text = re.sub(r'//.*', '', text)          # 删除单行注释
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)  # 删除多行注释
    return text

def extract_modules(content):
    """使用正则表达式提取模块名"""
    pattern = re.compile(
        r'\bmodule\s+([a-zA-Z_$][\w$]*)',  # 匹配module声明
        re.IGNORECASE | re.DOTALL
    )
    return pattern.findall(content)

def main():
    if len(sys.argv) != 2:
        print("Usage: python module.py <input.v>")
        sys.exit(1)

    input_file = sys.argv[1]

    with open(input_file, 'r') as f:
        content = remove_comments(f.read())

    modules = list(dict.fromkeys(extract_modules(content)))  # 去重保序

    for module in modules:
        print(module + ' ')

    #print(f"提取完成！共找到 {len(modules)} 个独立模块，已保存至 {output_file}")

if __name__ == "__main__":
    main()

