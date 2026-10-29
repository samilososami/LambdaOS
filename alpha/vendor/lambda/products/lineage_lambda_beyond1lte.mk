# LambdaOS alpha for the international Samsung Galaxy S10 (SM-G973F).
# Keep Samsung's device identity and hardware configuration, then add the
# Lambda-owned product layer.

$(call inherit-product, device/samsung/beyond1lte/lineage_beyond1lte.mk)
$(call inherit-product, vendor/lambda/config/common.mk)

# Keep the Lineage product prefix so its build hooks export kernel variables
# and other Soong configuration, while retaining LambdaOS as the OS identity.
PRODUCT_NAME := lineage_lambda_beyond1lte
PRODUCT_DEVICE := beyond1lte
PRODUCT_BRAND := samsung
PRODUCT_MODEL := SM-G973F
PRODUCT_MANUFACTURER := samsung

PRODUCT_SYSTEM_PROPERTIES += \
    ro.lambda.name=LambdaOS \
    ro.lambda.device=beyond1lte
