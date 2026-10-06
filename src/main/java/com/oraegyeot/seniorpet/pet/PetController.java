package com.oraegyeot.seniorpet.pet;

import com.oraegyeot.seniorpet.pet.PetDtos.CreatePetRequest;
import com.oraegyeot.seniorpet.pet.PetDtos.PetResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 반려동물 API. 사용자 id 는 항상 @CurrentUserId 로만 받는다. */
@RestController
@RequestMapping("/api/pets")
public class PetController {

    private final PetService petService;

    public PetController(PetService petService) {
        this.petService = petService;
    }

    @GetMapping
    public List<PetResponse> list(@CurrentUserId UUID userId) {
        return petService.list(userId).stream().map(PetResponse::from).toList();
    }

    @GetMapping("/{id}")
    public PetResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return PetResponse.from(petService.getOwned(userId, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PetResponse create(@CurrentUserId UUID userId, @Valid @RequestBody CreatePetRequest req) {
        return PetResponse.from(petService.create(userId, req));
    }

    @PutMapping("/{id}")
    public PetResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
                              @Valid @RequestBody CreatePetRequest req) {
        return PetResponse.from(petService.update(userId, id, req));
    }
}
