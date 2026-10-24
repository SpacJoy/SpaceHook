# 提交身份守卫（git hooks）

## 这个目录是干嘛的

`check-identity.sh` 是**提交前身份守卫**，防止 GUI/IDE Agent 用 git 自动兜底的假身份提交。

## 为什么需要它

git 在**读不到 `user.name` / `user.email`** 时不会报错停下，而是自己编一个身份提交：

```
fatal: unable to auto-detect email address (got 'SpaceJoy@PC-SpaceJoy.(none)')
```

实测本项目出现过两次（`SpaceJoy <spacejoy@local>`），分别在 2026-10-04 和 2026-10-24。
一旦提交就烧进提交对象，**只能重写历史**才能改（2026-10-25 已用 `filter-branch` 全库修正）。
根因是那个运行环境读不到 `~/.gitconfig`（HOME 指向别处 / 以服务身份运行 / 沙盒）。

## 安装

```bash
# 仓库根目录执行
cp tools/git-hooks/check-identity.sh .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

Windows 下 `chmod` 不生效也没关系，git 只要能执行该文件即可。

## 它拦什么

| 情况 | 结果 |
|---|---|
| `user.name`/`user.email` 都没配 | 拦（提示怎么配） |
| 邮箱形如 `*@local`、`*.\(none\)` | 拦（典型的自动兜底） |
| 邮箱与配置里的不一致 | 拦（提示临时换身份的正确写法） |
| 正常 | 放行 |

## 真要临时换身份提交

用一次性参数，绕过配置但不绕过人眼：

```bash
git -c user.name="某个名字" -c user.email="某个@邮箱" commit -m "..."
```

## 配套的 `.mailmap`

仓库根目录的 `.mailmap` 是**显示层双保险**：万一又混进假身份，
`git log` / GitHub 页面也会显示成真实身份。它不改变提交对象本身。

## 注意

`.git/hooks/` **不入版本库**，所以换机器 / 重新 clone 后要重新安装一次。
