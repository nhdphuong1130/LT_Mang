#include <stdio.h>
#include <string.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_bt.h"
#include "esp_gap_ble_api.h"
#include "esp_bt_main.h"
#include "esp_bt_device.h"
static const char *TARGET_DEVICE_NAME = "A53 al utilizatorului Deus";
#define PATH_LOSS_INDEX     2.5f
#define PROCESS_NOISE       0.015f
#define CALIBRATION_SAMPLES 100
float measurement_noise_R = 5.0f;
bool is_calibrated = false;
float dynamic_rssi_1m = -59.0f;
float calibration_sum = 0.0f;
float calibration_sq_sum = 0.0f;
int calibration_count = 0;


typedef struct {
    float Q;
    float R;
    float x_est_last;
    float P_last;
} KalmanFilter;


KalmanFilter kf;


void Kalman_Init(KalmanFilter* k, float process_noise, float measurement_noise, float initial_value) {
    k->Q = process_noise;
    k->R = measurement_noise;
    k->x_est_last = initial_value;
    k->P_last = 1.0f;
}


float Kalman_Update(KalmanFilter* k, float raw_val) {

    float P_temp = k->P_last + k->Q;
    float K = P_temp / (P_temp + k->R);
    float x_est = k->x_est_last + K * (raw_val - k->x_est_last);
    float P = (1 - K) * P_temp;
    k->P_last = P;
    k->x_est_last = x_est;
    return x_est;
}




static bool adv_data_find_name(uint8_t *adv_data, uint8_t adv_data_len, const char *target_name) {
    uint8_t *p_data = adv_data;
    uint8_t data_len = adv_data_len;
    while (data_len > 0) {
        uint8_t field_len = *p_data++;
        data_len--;
        if (field_len == 0 || data_len < field_len) break;
        uint8_t field_type = *p_data++;
        data_len--;

        if (field_type == 0x09 || field_type == 0x08) {
            uint8_t name_len = field_len - 1;
            if (name_len == strlen(target_name) && memcmp(p_data, target_name, name_len) == 0) {
                return true;
            }
        }
        p_data += (field_len - 1);
        data_len -= (field_len - 1);
    }
    return false;
}

static esp_ble_scan_params_t ble_scan_params = {
    .scan_type = BLE_SCAN_TYPE_ACTIVE,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .scan_filter_policy = BLE_SCAN_FILTER_ALLOW_ALL,
    .scan_interval = 0x50,
    .scan_window = 0x50,
    .scan_duplicate = BLE_SCAN_DUPLICATE_DISABLE
};

static void esp_gap_cb(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {


    if (event == ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT) {
        esp_ble_gap_start_scanning(0);
    }


    else if (event == ESP_GAP_BLE_SCAN_RESULT_EVT) {
        esp_ble_gap_cb_param_t *scan = (esp_ble_gap_cb_param_t *)param;

        if (scan->scan_rst.search_evt == ESP_GAP_SEARCH_INQ_RES_EVT) {


            if (adv_data_find_name(scan->scan_rst.ble_adv, scan->scan_rst.adv_data_len, TARGET_DEVICE_NAME)) {

    int raw_rssi = scan->scan_rst.rssi;

    if (!is_calibrated) {
        calibration_sum += raw_rssi;
        calibration_sq_sum += (raw_rssi * raw_rssi);
        calibration_count++;

        printf("CALIBRARE... %d/%d (Raw RSSI: %d)\n", calibration_count, CALIBRATION_SAMPLES, raw_rssi);

        if (calibration_count >= CALIBRATION_SAMPLES) {
            float mean_rssi = calibration_sum / (float)CALIBRATION_SAMPLES;
            dynamic_rssi_1m = mean_rssi;
            float variance = (calibration_sq_sum / (float)CALIBRATION_SAMPLES) - (mean_rssi * mean_rssi);
            if (variance < 0.1f) variance = 0.1f;
            measurement_noise_R = variance;


            Kalman_Init(&kf, PROCESS_NOISE, measurement_noise_R, dynamic_rssi_1m);

            is_calibrated = true;

            printf("\n--- CALIBRARE MEDIU COMPLETA ---\n");
            printf("Referinta (Mean) la 1m: %.2f dBm\n", dynamic_rssi_1m);
            printf("Zgomot Masurat (R): %.4f (Varianta calculata)\n", measurement_noise_R);
            printf("------------------------------\n\n");
        }
    }
    else {
        float filtered_rssi = Kalman_Update(&kf, (float)raw_rssi);
        float ratio = (dynamic_rssi_1m - filtered_rssi) / (10.0f * PATH_LOSS_INDEX);
        float dist_m = pow(10.0f, ratio);

        printf("%d, %.2f, %.2f\n", raw_rssi, filtered_rssi, dist_m);
    }
}
}
    }
}

void app_main(void) {
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    Kalman_Init(&kf, PROCESS_NOISE, measurement_noise_R, dynamic_rssi_1m);
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    esp_bt_controller_init(&bt_cfg);
    esp_bt_controller_enable(ESP_BT_MODE_BLE);
    esp_bluedroid_init();
    esp_bluedroid_enable();
    esp_ble_gap_register_callback(esp_gap_cb);
    esp_ble_gap_set_scan_params(&ble_scan_params);

    printf("--- SYSTEM READY ---\n");
    printf("Legend: Raw_RSSI, Filtered_RSSI, Distance(m)\n");
}