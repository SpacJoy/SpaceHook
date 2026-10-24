#!/bin/sh
# 提交前身份守卫：拒绝「不是配置里的真实身份」的提交。
#
# 为什么需要：git 在找不到 user.name / user.email 时会用「系统用户名@主机名」
# 自动兜底生成一个假身份（如 SpaceJoy <spacejoy@local>），提交后不可改，
# 只能重写历史。这个 hook 在提交前就拦下，避免污染历史。
#
# 安装：见仓库根目录 tools/git-hooks/README.md（或跑 install-hooks.sh）

expected_name=$(git config user.name)
expected_email=$(git config user.email)

if [ -z "$expected_name" ] || [ -z "$expected_email" ]; then
    echo "❌ 拒绝提交：git 没有配置 user.name / user.email" >&2
    echo "   请先设置，例如：" >&2
    echo "     git config --global user.name  \"你的名字\"" >&2
    echo "     git config --global user.email \"你的邮箱\"" >&2
    echo "   （当前生效身份来自哪里：git config --list --show-origin | grep user\.）" >&2
    exit 1
fi

actual_name=$(git var GIT_AUTHOR_IDENT | sed -e 's/ <.*//')
actual_email=$(git var GIT_AUTHOR_IDENT | sed -e 's/^.*<//' -e 's/>.*//')

# 典型的自动兜底特征：非邮箱形式的 local 域，或 (none) 后缀
case "$actual_email" in
    *@local|*.\(none\)|*@*.\(none\))
        echo "❌ 拒绝提交：检测到 git 自动兜底生成的假身份" >&2
        echo "   当前会用：$actual_name <$actual_email>" >&2
        echo "   配置里的：$expected_name <$expected_email>" >&2
        echo "   多半是运行环境读不到全局 git 配置（HOME 不对 / 以服务身份运行）。" >&2
        echo "   临时修：git -c user.name=\"...\" -c user.email=\"...\" commit ..." >&2
        exit 1
        ;;
esac

if [ "$actual_email" != "$expected_email" ]; then
    echo "❌ 拒绝提交：作者邮箱与配置不一致" >&2
    echo "   当前会用：$actual_name <$actual_email>" >&2
    echo "   配置里的：$expected_name <$expected_email>" >&2
    echo "   若确实要换身份，先改配置；只是临时用一次：" >&2
    echo "     git -c user.email=\"...\" commit ..." >&2
    exit 1
fi

exit 0
