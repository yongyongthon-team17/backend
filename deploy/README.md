# 배포 (GitHub Actions → EC2)

main에 머지되면 `.github/workflows/deploy.yml`이 테스트 → 빌드 → EC2 업로드 → systemd 재시작 → 기동 확인을 한다.
PR과 main push에서는 `ci.yml`이 테스트만 돌린다.

## 1. GitHub 설정 (한 번만)

레포 Settings → Secrets and variables → Actions 에 등록한다. (값은 채팅·레포에 적지 않는다)

| 이름 | 값 |
|---|---|
| `EC2_HOST` | 서버 IP 또는 도메인 |
| `EC2_USER` | SSH 사용자 (예: `ubuntu`) |
| `EC2_SSH_KEY` | 개인키(.pem) 파일의 **내용 전체** (`-----BEGIN ... END-----` 줄 포함) |
| `EC2_KNOWN_HOSTS` | 로컬에서 `ssh-keyscan -H <EC2_HOST>` 를 실행한 출력 |

Settings → Environments 에서 `production` 을 만들고 필요하면 승인자(Required reviewers)를 지정한다.

## 2. 서버 준비 (한 번만)

```bash
# Java 21, PostgreSQL
sudo apt update && sudo apt install -y openjdk-21-jre-headless postgresql

# 앱 디렉터리
sudo mkdir -p /opt/jigeumgayong && sudo chown ubuntu:ubuntu /opt/jigeumgayong

# 환경 파일 (값은 서버에서 직접 입력한다)
sudo mkdir -p /etc/jigeumgayong
sudo nano /etc/jigeumgayong/env      # 아래 예시 참고
sudo chmod 600 /etc/jigeumgayong/env

# 서비스 등록
sudo cp deploy/jigeumgayong.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable jigeumgayong
```

`/etc/jigeumgayong/env` 예시 (값은 비워 둔 양식):

```
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://localhost:5432/jigeumgayong
DB_USERNAME=
DB_PASSWORD=
OPENAI_API_KEY=
CORS_ALLOWED_ORIGINS=
```

## 3. DB 준비 (한 번만)

prod는 `ddl-auto: validate` 라서 테이블을 먼저 만들어야 한다.

```bash
psql -h localhost -U <계정> -d jigeumgayong -f src/main/resources/db/schema-postgres.sql
```

시설 데이터는 처음 한 번만 `--app.etl.enabled=true` 로 적재한다. (prod에는 자동 적재가 없다)
`/etc/jigeumgayong/env` 에 `APP_ETL_ENABLED=true` 를 잠시 넣고 재시작한 뒤, 적재 로그를 확인하고 지운다.

## 4. 롤백

이전 jar가 `/opt/jigeumgayong/app.jar.prev` 에 남아 있다.

```bash
cd /opt/jigeumgayong && mv app.jar app.jar.bad && mv app.jar.prev app.jar
sudo systemctl restart jigeumgayong
```

## 5. 주의

- 서버 보안그룹에서 SSH(22)는 필요한 IP만 허용한다. GitHub Actions 러너 IP는 고정이 아니라서 22번을 열어 두어야 배포가 되는데,
  키 인증만 허용하고 비밀번호 로그인은 꺼 둔다.
- 프론트(Vercel)가 HTTPS 이므로 백엔드도 HTTPS 로 제공해야 브라우저가 호출한다. (도메인과 인증서는 별도 결정)
- 한 서버 한 인스턴스를 전제로 한 설정이다.
