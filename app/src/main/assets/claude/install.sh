#!/bin/bash
# 可选组件单独安装；失败保留原版本，不改变 Ubuntu 和 DSH 的环境身份。
set -euo pipefail
umask 077
assets=/usr/local/share/dsha/claude
base=/root/.local/share/dsha-claude
version=2.1.283-sdk0.3.283
mkdir -p "$base"
target="$base/$version"
if [ ! -f "$target/.ready" ]; then
    stage=$(mktemp -d "$base/install.XXXXXXXX")
    cp "$assets/package.json" "$assets/package-lock.json" "$stage/"
    npm ci --prefix "$stage" --no-audit --no-fund
    "$stage/node_modules/.bin/claude" --version
    touch "$stage/.ready"
    # 不覆盖失败现场或同名目录；重新安装可以使用新的版本目录。
    if [ -e "$target" ]; then
        printf '%s\n' '安装目录已存在但未就绪，请在终端检查后再安装。' >&2
        exit 1
    fi
    mv "$stage" "$target"
fi
"$target/node_modules/.bin/claude" --version
link="$base/current.$$"
ln -s "$version" "$link"
mv -Tf "$link" "$base/current"
printf '%s\n' 'Claude Code 安装完成。可填写 API Key，或进入登录终端。'
