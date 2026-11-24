# Build only for the user's arm64-v8a Android phone.
APP_ABI := arm64-v8a
APP_PLATFORM := android-24
APP_OPTIM := release
APP_CFLAGS := -O3 -DANDROID
# Android 15+ devices may use 16 KB memory pages.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
