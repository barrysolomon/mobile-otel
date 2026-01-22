# Quick Reference Card

## 🚀 Get Your Dash0 Token

1. Visit https://app.dash0.com
2. Go to **Settings** → **API Tokens**
3. Click **Create Token**
4. Copy the token (starts with `auth_`)

---

## 🔧 Configure Local Development

```bash
cd examples/demo-app/android

# Edit local.properties
nano local.properties

# Replace this line:
dash0.authToken=PASTE_YOUR_DASH0_TOKEN_HERE

# With your actual token:
dash0.authToken=auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh
```

**Your current configuration:**
- ✅ Region: **US** (`https://ingress.dash0.com:4317`)
- ✅ Dataset: `mobile-production`
- ⏳ Token: **Needs to be pasted**

---

## 📱 Build Commands

```bash
# Debug build (local collector)
./gradlew installDebug

# Release build (Dash0 US region)
./gradlew assembleRelease
```

---

## ✅ Verify Configuration

```bash
# Check token is set
cat local.properties | grep authToken

# Should show:
# dash0.authToken=auth_...

# Build and check generated config
./gradlew processConfigTemplate
cat src/release/assets/otel-config.json | grep "ingress.dash0.com"
```

---

## 📊 View Data in Dash0

1. Open https://app.dash0.com
2. Navigate to **Services**
3. Look for: `otel-mobile-demo`
4. View traces, logs, and metrics

---

## 🐛 Troubleshooting

### Token format is wrong
```bash
# Valid token format:
auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh

# Invalid formats:
Bearer auth_...  ❌ (remove "Bearer")
"auth_..."       ❌ (remove quotes)
```

### No data appearing
```bash
# Check app logs
adb logcat | grep -i "otel\|dash0"

# Look for:
# ✅ "Successfully exported" messages
# ❌ "401 Unauthorized" = bad token
# ❌ "Connection refused" = wrong endpoint
```

### Build fails
```bash
# Clean and rebuild
./gradlew clean
./gradlew assembleRelease --info | grep dash0
```

---

## 🔒 Security Checklist

- [x] local.properties is in .gitignore
- [x] Token uses PASTE_YOUR_DASH0_TOKEN_HERE placeholder
- [ ] Replace placeholder with real token
- [ ] Never commit local.properties
- [ ] Rotate token every 90 days

---

## 🌍 Region Configuration

**Current:** US Region (Default)
- Endpoint: `https://ingress.dash0.com:4317`
- Best for: Americas, Asia-Pacific

**Alternative:** EU Region
- Endpoint: `https://ingress.eu-west-1.aws.dash0.com:4317`
- Best for: Europe, Africa, Middle East
- To switch: Edit `dash0.endpoint` in local.properties

---

## 📞 Need Help?

- 📖 **Full Guide**: [SETUP.md](SETUP.md)
- 🔧 **Configuration**: [CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md)
- 🌍 **Global Deployment**: [GLOBAL_DEPLOYMENT.md](GLOBAL_DEPLOYMENT.md)
- 💬 **Dash0 Support**: support@dash0.com
