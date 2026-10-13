package com.oraegyeot.seniorpet.pet;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.common.AppProperties;
import com.oraegyeot.seniorpet.common.PatchTextInput;
import com.oraegyeot.seniorpet.pet.PetDtos.CreatePetRequest;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반려동물 서비스 — "소유자 검증 서비스 패턴"의 기준 예시.
 *
 * 1) 모든 public 메서드의 첫 파라미터는 로그인 사용자 id(userId)다.
 * 2) 단건 접근은 getOwned(userId, id) 로만 한다. 남의 것/없는 것 모두 404 (존재 여부 비노출).
 * 3) 생성 시 엔티티의 userId 는 요청 본문이 아니라 파라미터 userId 로 채운다.
 * 다른 리소스(medications, daily_logs 등)의 서비스도 이 구조를 그대로 따른다.
 */
@Service
public class PetService {

    private static final LocalDate MIN_DATE = LocalDate.of(1980, 1, 1);

    private final PetRepository petRepository;
    private final EntityManager em;
    private final Clock clock;
    private final ZoneId zone;

    public PetService(PetRepository petRepository, EntityManager em, Clock clock, AppProperties props) {
        this.petRepository = petRepository;
        this.em = em;
        this.clock = clock;
        this.zone = props.zoneId();
    }

    @Transactional(readOnly = true)
    public List<Pet> list(UUID userId) {
        return petRepository.findAllByUserId(userId);
    }

    /** 본인 반려동물 1건. 다른 서비스(투약, 일일 기록)가 pet_id 를 받을 때도 이 메서드로 먼저 검증한다. */
    @Transactional(readOnly = true)
    public Pet getOwned(UUID userId, UUID petId) {
        return petRepository.findByIdAndUserId(petId, userId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Pet create(UUID userId, CreatePetRequest req) {
        // MVP: 사용자당 1마리 (DB 의 pets_one_per_user 인덱스와 같은 규칙)
        if (petRepository.existsByUserId(userId)) {
            throw petLimit();
        }
        Profile profile = resolveProfile(req, null);
        Pet pet = new Pet(userId, req.name().trim(), req.species(), profile.birthYear(), blankToNull(req.conditions()),
                profile.profileType(), profile.birthDate(), profile.adoptedOn());
        try {
            return saveAndRefresh(pet);
        } catch (DataIntegrityViolationException e) {
            throw petLimit(); // 동시 요청으로 unique 인덱스에 걸린 경우
        }
    }

    @Transactional
    public Pet update(UUID userId, UUID petId, CreatePetRequest req) {
        Pet pet = getOwned(userId, petId);
        Profile profile = resolveProfile(req, pet);
        pet.update(req.name().trim(), req.species(), profile.birthYear(), blankToNull(req.conditions()),
                profile.profileType(), profile.birthDate(), profile.adoptedOn());
        return saveAndRefresh(pet);
    }

    private record Profile(Short birthYear, String profileType, LocalDate birthDate, LocalDate adoptedOn) {
    }

    /**
     * 유형·생일·입양일 결정. current 가 null(생성)이면 생략 = null, 있으면(PUT) 생략 = 기존 값 유지.
     * 날짜는 달력 날짜(app.zone)이며 기록 날짜(새벽 4시 규칙)와 무관하다. 값은 로그에 남기지 않는다.
     */
    private Profile resolveProfile(CreatePetRequest req, Pet current) {
        String type = PatchTextInput.resolve(req.profileType(), current == null ? null : current.getProfileType());
        if (type != null && !type.equals("first_time") && !type.equals("senior")) {
            throw ApiException.validation("profileType: first_time 또는 senior 이어야 합니다.");
        }
        Short birthYear = req.birthYear();

        LocalDate birthDate;
        if (req.birthDate() == null) {
            birthDate = current == null ? null : current.getBirthDate();
            // 옛 클라이언트가 연도만 바꾼 경우: 저장된 생일 날짜와 모순되므로 날짜를 지운다
            if (birthDate != null && (birthYear == null || birthYear != birthDate.getYear())) {
                birthDate = null;
            }
        } else {
            birthDate = parseDate("birthDate", req.birthDate().value());
            if (birthDate != null) {
                checkRange("birthDate", birthDate);
                if (birthYear != null && birthYear != birthDate.getYear()) {
                    throw ApiException.validation("birthDate: 생일의 연도가 birthYear 와 같아야 합니다.");
                }
                birthYear = (short) birthDate.getYear();
            }
        }

        LocalDate adoptedOn;
        if (req.adoptedOn() == null) {
            adoptedOn = current == null ? null : current.getAdoptedOn();
        } else {
            adoptedOn = parseDate("adoptedOn", req.adoptedOn().value());
            if (adoptedOn != null) {
                checkRange("adoptedOn", adoptedOn);
            }
        }
        if (birthDate != null && adoptedOn != null && adoptedOn.isBefore(birthDate)) {
            throw ApiException.validation("adoptedOn: 입양일은 생일보다 빠를 수 없습니다.");
        }
        return new Profile(birthYear, type, birthDate, adoptedOn);
    }

    private static LocalDate parseDate(String field, String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text); // ISO yyyy-MM-dd, 2/30 같은 날짜는 예외
        } catch (DateTimeParseException e) {
            throw ApiException.validation(field + ": 존재하는 날짜를 YYYY-MM-DD 형식으로 입력하세요.");
        }
    }

    /** 1980-01-01 이상, 오늘(app.zone 달력 날짜) 이하. */
    private void checkRange(String field, LocalDate date) {
        if (date.isBefore(MIN_DATE)) {
            throw ApiException.validation(field + ": 1980-01-01 이후 날짜여야 합니다.");
        }
        if (date.isAfter(clock.instant().atZone(zone).toLocalDate())) {
            throw ApiException.validation(field + ": 미래 날짜는 입력할 수 없습니다.");
        }
    }

    /** 사진 경로 교체 결과. oldPath 는 호출한 쪽이 DB 반영 뒤 파일을 지우는 데 쓴다. */
    public record PhotoChange(Pet pet, String oldPath) {
    }

    /** 사진 경로를 newPath(null 이면 삭제)로 바꾸고 이전 경로를 돌려준다. */
    @Transactional
    public PhotoChange changePhotoPath(UUID userId, UUID petId, String newPath) {
        Pet pet = getOwned(userId, petId);
        String oldPath = pet.getPhotoPath();
        pet.changePhotoPath(newPath);
        return new PhotoChange(saveAndRefresh(pet), oldPath);
    }

    /** updated_at 은 DB 트리거(UPDATE)가 now() 로 덮어쓰므로, 응답이 저장된 값과 같도록 flush 뒤 다시 읽는다. */
    private Pet saveAndRefresh(Pet pet) {
        petRepository.saveAndFlush(pet);
        em.refresh(pet);
        return pet;
    }

    private static ApiException petLimit() {
        return new ApiException(HttpStatus.CONFLICT, "PET_LIMIT_REACHED",
                "현재는 반려동물을 1마리만 등록할 수 있습니다.");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
