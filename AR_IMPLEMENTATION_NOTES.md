# AR trajectory implementation notes

Baseline: a79ccf5c7a4ccddb954f38682bd4bbc3262c7855

Implementation scope:
- ENU anchor at the continuously matched route point; preserve filtered camera optical-center offset.
- DEM bilinear interpolation across tile boundaries, 48-entry memory LRU, 300 MB disk LRU, per-sample fallback.
- Persist calibrated camera height (default 1.45 m; range 1.1–1.9 m).
- Adaptive pose filtering, magnetic reliability weighting, and guarded low-gain GPS course correction.
- Use the bound physical camera characteristics and calibrated intrinsics when valid.
- Curvature/elevation-aware route sampling, bevel joins, and near-plane triangle clipping.
- Confidence-graded DEM occlusion with temporal/spatial fade.
- GPS quality/freshness gating, immutable frame snapshots, and diagnostics.
- Preserve existing navigation, map, camera, location, and hike-recording behavior.
- Add unit tests for interpolation, fallback, adaptive sampling, and near-plane clipping.

Deferred: ARCore Depth, HMM/Viterbi rewrite, and offline-area predownload UI.
