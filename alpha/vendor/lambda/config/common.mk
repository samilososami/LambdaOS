# LambdaOS alpha product additions for the LineageOS 22.2 source tree.
# Inherit AFTER device/samsung/beyond1lte/lineage_beyond1lte.mk.
# The stage step must copy apps/Lambda to packages/apps/Lambda and place the
# hash-verified approved ZIP at vendor/lambda/prebuilt/bootanimation.zip.

PRODUCT_PACKAGES += Lambda

# Resource-only accessibility refinements; platform-signed and enabled at boot.
PRODUCT_PACKAGES += LambdaAccessibilityFramework LambdaAccessibilitySystemUI

# Keep ADB available from the first boot of this userdebug alpha while retaining
# secure host authentication. The staged file may contain several public keys,
# but the private keys never enter the Android source tree or resulting image.
PRODUCT_ADB_KEYS := vendor/lambda/security/adb_keys

PRODUCT_COPY_FILES += \
    vendor/lambda/config/init.lambda.adb.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/init.lambda.adb.rc

PRODUCT_SYSTEM_PROPERTIES += \
    ro.lambda.version=0.1.0-alpha.2 \
    ro.lambda.channel=alpha

TARGET_BOOTANIMATION := vendor/lambda/prebuilt/bootanimation.zip
TARGET_BOOTANIMATION_HALF_RES := false
TARGET_SCREEN_WIDTH := 1440
TARGET_SCREEN_HEIGHT := 3040

# Preserve upstream device identity and SELinux policy. up_param is not part of
# the OTA: the existing Lambda v7 artwork stays in place on the reference phone.
# Kernel, Magisk and NetHunter packaging are release gates, not assumed features.
