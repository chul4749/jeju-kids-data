# jeju-kids-data

제주 육아 앱 「제주 아이랑」의 중앙 수집기입니다.

- 1시간마다 GitHub Actions가 공공 API 등(관광공사 TourAPI, 비짓제주, KOPIS, 기상청, 에어코리아, 교육청·시청 공개 목록, 카카오 로컬·검색)에서 정보를 모아
  `https://chul4749.github.io/jeju-kids-data/v1/*.json` 으로 공개합니다.
- 앱은 이 파일만 받으므로 API 키가 앱에 없고, 사용자 수와 관계없이 API 호출은 여기서 한 번입니다.
- API 키는 저장소 Secrets에만 있습니다.
- `src/main/kotlin/com/moon/jejukids/` 의 `data`·`logic` 은 앱과 같은 코드입니다(앱 저장소의 `tools/sync_collector.py`로 복사).

개인정보처리방침: https://chul4749.github.io/jeju-kids-data/privacy.html
