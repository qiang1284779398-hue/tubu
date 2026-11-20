# Generated 2D sprite assets

Generated character images belong in `game-assets/sprites/`.

## Upload a generated image

Use `tools/upload_sprite.py` to commit a PNG/JPEG/WebP image to the repository.

The script reads the image as binary bytes, Base64-encodes it for GitHub's Contents API, and creates or updates the file. It does **not** train an AI model; it stores the actual image asset.

Required:
- A GitHub personal access token with **Contents: Read and write** access to this repository.
- Set it as the `GITHUB_TOKEN` environment variable. Do not write the token into the script or commit it.

Example:

```bash
export GITHUB_TOKEN="YOUR_TOKEN"
python tools/upload_sprite.py ./guan_yu_idle.png --name guan_yu_idle.png
```

Recommended naming:
- `liu_bei/`
- `guan_yu/`
- `zhang_fei/`

For animation, store each frame as an individual PNG or a sprite sheet, and include a small JSON manifest describing frame order and frame rate.
