#!/bin/sh
# Gradle wrapper script (简化版，建议用 Android Studio 自动生成)
DIR="$(cd "$(dirname "$0")" && pwd)"
exec gradle -p "$DIR" "$@"
