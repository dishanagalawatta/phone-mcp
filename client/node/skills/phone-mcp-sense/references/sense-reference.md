# Sense reference

## Arg clamps

| Tool | Args |
|---|---|
| `sensors_read` | `types[]` from accelerometer, gyroscope, magnetic_field, pressure, light, proximity, gravity, linear_acceleration, rotation_vector, humidity, ambient_temperature; `timeoutMs` 100..10000 default 2000. |
| `sensors_stream` | `types[]` default IMU subset; `intervalMs` 100..2000 default 200; `durationMs` 1000..60000 default 10000; `includeLocation` default true. |
| `location_read` | No args. |
| `battery_read` | No args. Returns `percent`, `charging`, `full`, `pluggedAc/Usb/Wireless`, `temperatureC`. |
| `device_info` | No args. Returns model, API, app version, protocol, uptime, `tlsSha256`. |

## Batch shape (`notifications/sensors`)

```json
{"t": 1729000000000, "sensors": {"accelerometer": [0.1, 9.8, 0.2]},
 "location": {"lat": 0.0, "lon": 0.0, "acc": 12.0},
 "warnings": ["missing sensors: humidity"]}
```

Final envelope: `{"tool": "sensors_stream", "batches": N}`.

## Notes

- Snapshot defaults read accelerometer, gyroscope, magnetic_field, pressure, light, proximity.
- `sensors_stream` without SSE returns an arg error; call `sensors_read` instead for unary paths.
- `includeLocation:true` while Location is kill-switched returns `-32602`; either enable Location or pass false.
- Emulator GPS needs a mock route; otherwise `location_read` reports unavailable.
- Compare `device_info.tlsSha256` against the dashboard pin on every new pairing.
