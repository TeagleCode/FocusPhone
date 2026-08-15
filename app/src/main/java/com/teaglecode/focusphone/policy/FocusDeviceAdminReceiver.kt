package com.teaglecode.focusphone.policy

import android.app.admin.DeviceAdminReceiver

/**
 * Required by DevicePolicyManager. Provisioned once via:
 *   adb shell dpm set-device-owner com.teaglecode.focusphone/.policy.FocusDeviceAdminReceiver
 * on a device with no accounts configured.
 */
class FocusDeviceAdminReceiver : DeviceAdminReceiver()
