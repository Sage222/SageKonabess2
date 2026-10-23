# Syncing Pre-Compiled APK (`releases/SageKonaBess-v1.0.apk`) & GitHub Workflow

This plan resolves the `404` on `releases/SageKonaBess-v1.0.apk` by replicating the exact release structure used in `Sage222/SageSDKernel`.

## User Review & Critical Decisions

> [!IMPORTANT]
> **Root Cause Identified (from inspecting `Sage222/SageSDKernel` vs `Sage222/SageKonabess2`)**:
> 1. When we moved `app-debug.apk` into `releases/SageKonaBess-v1.0.apk` using a filesystem rename (`move`), the file retained its old modification timestamp (`mtime` from the earlier build), causing AI Studio's post-turn GitHub sync delta to skip the file.
> 2. Writing a fresh copy of the compiled APK to `releases/SageKonaBess-v1.0.apk` with an updated current timestamp (exactly as done in `Sage222/SageSDKernel` commit `41fd045`) ensures AI Studio's workspace tracker commits `releases/SageKonaBess-v1.0.apk` directly to your GitHub repository.

---

## 1. Planned Changes

1. **Generate Fresh Tracked `releases/SageKonaBess-v1.0.apk`**:
   - Copy the freshly compiled `.build-outputs/app-debug.apk` / `app/build/outputs/apk/debug/app-debug.apk` into `releases/SageKonaBess-v1.0.apk` with a newly updated modification timestamp (`touch`) and compute its SHA-256 checksum so AI Studio commits the binary directly into `releases/SageKonaBess-v1.0.apk` just like `releases/KalamaForge-sm8550-v1.0.apk` in `SageSDKernel`.
2. **Match `Sage222/SageSDKernel` GitHub Actions Workflow (`.github/workflows/android-apk.yml`)**:
   - Replace the failing `setup-android` workflow with the clean, proven `.github/workflows/android-apk.yml` from `Sage222/SageSDKernel` that uploads `releases/SageKonaBess-v1.0.apk` as a workflow artifact and attaches it to GitHub Releases.
3. **Update `README.md` with Direct Repo APK Link & SHA-256 Table**:
   - Format the **Pre-Compiled APK Download** table at the top of `README.md` identically to `Sage222/SageSDKernel` with the direct link to [`releases/SageKonaBess-v1.0.apk`](releases/SageKonaBess-v1.0.apk), application ID (`com.aistudio.sagekonabess.rxvq`), file size, and SHA-256 checksum.
