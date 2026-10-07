package com.oraegyeot.seniorpet.push;

import com.oraegyeot.seniorpet.push.DeviceTokenDtos.DeviceRequest;
import com.oraegyeot.seniorpet.push.DeviceTokenDtos.DeviceResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 기기 토큰 등록·해제 API (docs/api-reminders.md 3절). 클라이언트는 로그아웃 직전에 DELETE 를 호출해야 한다. */
@RestController
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @PutMapping("/api/devices")
    public DeviceResponse register(@CurrentUserId UUID userId, @Valid @RequestBody DeviceRequest req) {
        return DeviceResponse.from(deviceTokenService.register(userId, req));
    }

    @DeleteMapping("/api/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@CurrentUserId UUID userId, @PathVariable UUID id) {
        deviceTokenService.unregister(userId, id);
    }
}
