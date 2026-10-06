-- 자동 테스트(./gradlew test) 전용 DB. 개발 DB(seniorpet)와 데이터를 섞지 않기 위함.
-- 컨테이너 볼륨이 처음 만들어질 때 한 번만 실행된다.
create database seniorpet_test owner seniorpet;
