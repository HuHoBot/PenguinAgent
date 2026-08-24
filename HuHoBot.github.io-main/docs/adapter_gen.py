import toml
import mkdocs_gen_files

from urllib.parse import urlparse


def parse_github_url(url):
    parsed_url = urlparse(url)
    
    if parsed_url.netloc != "github.com":
        raise ValueError("The provided URL is not a valid GitHub URL")

    path_parts = parsed_url.path.strip("/").split("/")
    
    if len(path_parts) < 2:
        raise ValueError("Invalid URL format. Unable to extract organization and repository names")
    
    organization = path_parts[0]
    repository = path_parts[1]
    
    return organization, repository

with mkdocs_gen_files.open("Adapter/Third-Party.md", "w") as f:
    def fp(str):
        print(str, file=f)

    def el():
        print("", file=f)

    fp("---")
    fp("comments: true")
    fp("hide:")
    fp("  - navigation")
    fp("  - toc")
    fp("---")
    el()

    fp("# 第三方适配器")
    el()
    fp("本页包含一些HuHoBot的第三方适配器")
    el()

    fp("## 添加适配器")
    el()
    fp("您可以在网站上添加自己的适配器，只需打开一个[新Pull Request](https://github.com/HuHoBot/HuHoBot.github.io/pulls)即可。")
    el()

    fp("## 可用适配器列表")
    el()
    fp("<div class=\"grid cards\" markdown>")
    el()

    config = toml.load("docs/adapter.toml")
    for name, info in config.items():
        description = info["description"]
        authors = info["authors"]
        github = info["github"]
        user, repo = parse_github_url(github)


        fp(f"-   **{name}** <small>by {", ".join(authors)}</small>")
        el()
        fp(f"    ![Stars](https://img.shields.io/github/stars/{user}/{repo}?style=flat-square)")
        fp(f"    ![License](https://img.shields.io/github/license/{user}/{repo}?style=flat-square)")
        fp(f"    ![Last Commit](https://img.shields.io/github/last-commit/{user}/{repo}?style=flat-square)")
        fp(f"    ![Top Language](https://img.shields.io/github/languages/top/{user}/{repo}?style=flat-square)")
        el()
        fp("    ---")
        el()
        fp(f"    {description}")
        el()
        fp(f"    [**:octicons-arrow-right-24: GitHub**]({github})")
        el()

    el()
    fp("</div>")