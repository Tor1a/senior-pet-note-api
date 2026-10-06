package com.oraegyeot.seniorpet.pet;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.util.UUID;

/** 반려동물 저장소. OwnedRepository 규칙(모든 조회에 userId 필수)을 따른다. */
public interface PetRepository extends OwnedRepository<Pet, UUID> {
}
