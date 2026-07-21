#!/bin/bash
set -e

# ---- Config ----
OUTPUT="auxio.tar.gz"
FILELIST="filelist.txt"

# ---- Folders/paths to exclude (relative to repo root) ----
EXCLUDES=(
  "./media/libraries"
  "./app/build"
  "./musikr/build"
  "./musikr/.cxx"
  "./build"
  "./musikr/src/main/cpp/taglib/build"
  "./musikr/src/main/cpp/taglib/pkg"
  "./.gradle"
  "./.git"
)

echo "Building file list (excluding build artifacts and caches)..."

# Build the find command dynamically with -prune for each exclude
FIND_ARGS=()
for path in "${EXCLUDES[@]}"; do
  FIND_ARGS+=(-path "$path" -prune -o)
done

find . "${FIND_ARGS[@]}" -type f -print > "$FILELIST"

NUM_FILES=$(wc -l < "$FILELIST")
echo "Found $NUM_FILES files to include."

echo "Creating archive: $OUTPUT ..."
tar -czf "$OUTPUT" -T "$FILELIST"

echo ""
echo "Done."
du -sh "$OUTPUT"

echo ""
echo "Top 20 largest items in the archive (sanity check):"
tar -tzf "$OUTPUT" | xargs -I{} du -sh "{}" 2>/dev/null | sort -rh | head -20

# Cleanup temp file
rm -f "$FILELIST"
