# F-Droid & Repository Deployment Guide

This guide details how to publish **Handshake DNS Resolver** (`org.handshake.resolver`) to your personal F-Droid repository on `homeserver:/var/mreugenej7-repo`.

---

## 1. Release Signing Verification

The release APK is configured in [app/build.gradle.kts](file:///home/mreugenej7/git/android-hns-resolver/app/build.gradle.kts) to use the same signing key as `bananify-my-pic` (`signingConfigs.getByName("debug")` from `~/.android/debug.keystore`).

Both applications share the exact same certificate fingerprint:
- **Signer DN**: `C=US, O=Android, CN=Android Debug`
- **SHA-256 Digest**: `4169e961d06b7ff32566ee615964bf674286b9ee90582bb7d196cecaf5a1c856`
- **SHA-1 Digest**: `5c98025ce7b05918a9df541bd6e5c8440ef22955`

Compiled release APK location:
`app/build/outputs/apk/release/app-release.apk`

---

## 2. Prepared Repository Files

| File | Destination on `homeserver` | Description |
|---|---|---|
| [org.handshake.resolver.yml](file:///home/mreugenej7/git/android-hns-resolver/distribution/fdroid/org.handshake.resolver.yml) | `/var/mreugenej7-repo/metadata/org.handshake.resolver.yml` | F-Droid application metadata |
| [icon.png](file:///home/mreugenej7/git/android-hns-resolver/distribution/fdroid/icon.png) | `/var/mreugenej7-repo/metadata/org.handshake.resolver/en-US/images/icon.png` | 512x512 PNG app icon for F-Droid client |
| [index.html](file:///home/mreugenej7/git/android-hns-resolver/distribution/fdroid/index.html) | `/var/mreugenej7-repo/index.html` | Updated catalog page with Handshake DNS Resolver card |
| [index_snippet.html](file:///home/mreugenej7/git/android-hns-resolver/distribution/fdroid/index_snippet.html) | — | Raw HTML card snippet for insertion into existing `index.html` |

---

## 3. Deployment Commands

From your local machine (within `android-hns-resolver/`):

```bash
# 1. Copy the signed release APK to the repo directory
scp app/build/outputs/apk/release/app-release.apk homeserver:/tmp/hns-resolver-1.0.1.apk

# 2. Copy the metadata YAML, icon and updated index.html
scp distribution/fdroid/org.handshake.resolver.yml homeserver:/tmp/org.handshake.resolver.yml
scp distribution/fdroid/icon.png homeserver:/tmp/org.handshake.resolver.icon.png
scp distribution/fdroid/index.html homeserver:/tmp/index.html
```

Then SSH into `homeserver` and move the files into place:

```bash
ssh homeserver

# Move files into /var/mreugenej7-repo
sudo mv /tmp/hns-resolver-1.0.1.apk /var/mreugenej7-repo/repo/
sudo chown caddy:caddy /var/mreugenej7-repo/repo/hns-resolver-1.0.1.apk

sudo mv /tmp/org.handshake.resolver.yml /var/mreugenej7-repo/metadata/
sudo chown caddy:caddy /var/mreugenej7-repo/metadata/org.handshake.resolver.yml

# Move icon to metadata fastlane path and repo icons
sudo mkdir -p /var/mreugenej7-repo/metadata/org.handshake.resolver/en-US/images
sudo cp /tmp/org.handshake.resolver.icon.png /var/mreugenej7-repo/metadata/org.handshake.resolver/en-US/images/icon.png
sudo mv /tmp/org.handshake.resolver.icon.png /var/mreugenej7-repo/repo/icons/org.handshake.resolver.png 2>/dev/null || true
sudo chown -R caddy:caddy /var/mreugenej7-repo/metadata/org.handshake.resolver

sudo mv /tmp/index.html /var/mreugenej7-repo/index.html
sudo chown root:root /var/mreugenej7-repo/index.html

# Create direct download symlink matching index.html
cd /var/mreugenej7-repo
sudo ln -sf repo/hns-resolver-1.0.1.apk HnsResolver.apk

# Regenerate F-Droid repository indexes
sudo -u caddy fdroid update
```
