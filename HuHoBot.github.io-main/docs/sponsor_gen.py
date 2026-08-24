import json
import os
from pathlib import Path

# 路径配置
BASE_DIR = ""
SPONSOR_JSON = "docs/sponsor.json"
SPONSOR_MD = "docs/Sponsor.md"
OUTPUT_MD = "docs/Sponsor.md"  # 直接覆盖原文件

def generate_sponsor_table():
    # 读取赞助者数据
    with open(SPONSOR_JSON, 'r', encoding='utf-8') as f:
        sponsors = json.load(f)
    
    # 按赞助金额从大到小排序
    sponsors.sort(key=lambda x: x['amount'], reverse=True)
    
    # 生成表格行
    table_rows = []
    for sponsor in sponsors:
        # 处理头像
        avatar = ""
        if "QQ" in sponsor and sponsor["QQ"]:
            avatar = f"![头像](http://q1.qlogo.cn/g?b=qq&nk={sponsor['QQ']}&s=100)"
        elif "imgUrl" in sponsor and sponsor["imgUrl"]:
            avatar = f"![头像]({sponsor['imgUrl']})"
        
        # 处理留言 (空留言用空格占位)
        message = sponsor.get("message", " ")
        if not message.strip():
            message = " "
            
        # 生成行，在amount后添加CNY
        row = f"| {avatar} | **{sponsor['name']}** | {message} | {sponsor['amount']}CNY |"
        table_rows.append(row)
    
    return "\n".join(table_rows)

def update_markdown_file():
    # 读取原Markdown文件
    with open(SPONSOR_MD, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 生成表格内容
    sponsor_table = generate_sponsor_table()
    
    # 替换占位符
    updated_content = content.replace("$SponsorList$", sponsor_table)
    
    # 写回文件
    with open(OUTPUT_MD, 'w', encoding='utf-8') as f:
        f.write(updated_content)

if __name__ == "__main__":
    print("正在生成赞助者列表...")
    update_markdown_file()
    print("赞助者列表生成完成！")