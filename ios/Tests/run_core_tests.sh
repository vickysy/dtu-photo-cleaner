#!/bin/sh
# 编译 Core 规则 + 测试，跑一遍。在 ios 目录下运行：sh Tests/run_core_tests.sh
set -e
cd "$(dirname "$0")/.."
work="${TMPDIR:-/tmp}/qingle-core-tests"
mkdir -p "$work"
# 测试写的是顶层代码，swiftc 只认叫 main.swift 的文件
cp Tests/CoreTests.swift "$work/main.swift"
swiftc -O QingleAlbum/Core/*.swift "$work/main.swift" -o "$work/run"
"$work/run"
