#!/usr/bin/env bash
# Contrôle avant déploiement : démarre le back sur une base MySQL 8.0 vide et vérifie que Liquibase crée tout.
# Usage (depuis la racine du dépôt, après « mvn package » du back) :
#   bash swiftcheck-aml-tools/liquibase-baseline/check-empty-db.sh
# Sortie 0 si l'application démarre, 1 sinon (le journal est dans /tmp/swiftcheck-empty-db.log).
set -u
JAR=swiftcheck-aml-core/back/target/swiftcheck-aml-back-0.0.1-SNAPSHOT.jar
LOG=/tmp/swiftcheck-empty-db.log
PORT_DB=3317
NAME=swiftcheck-empty-db-check

docker rm -f "$NAME" >/dev/null 2>&1
docker run -d --name "$NAME" -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=swift_empty -e MYSQL_USER=swift -e MYSQL_PASSWORD=swift \
  -p "$PORT_DB:3306" mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci >/dev/null
for _ in $(seq 1 40); do docker exec "$NAME" mysqladmin -uroot -ptest ping 2>/dev/null | grep -q alive && break; sleep 2; done
sleep 5

PLUGINS=$(mktemp -d)
SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$PORT_DB/swift_empty?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true" \
SPRING_DATASOURCE_USERNAME=swift SPRING_DATASOURCE_PASSWORD=swift SERVER_PORT=18087 PF4J_PLUGINS_DIR="$PLUGINS" \
  timeout 300 java -Dswiftcheck.ingestion.initial-delay=P1D -Dswiftcheck.ingestion.check-interval=P1D -jar "$JAR" > "$LOG" 2>&1 &
PID=$!
STATUS=1
for _ in $(seq 1 60); do
  sleep 5
  if grep -q 'Started .*Application' "$LOG"; then STATUS=0; break; fi
  if grep -q 'APPLICATION FAILED' "$LOG"; then break; fi
done
kill "$PID" >/dev/null 2>&1
echo "changeSets exécutés : $(grep -c 'ran successfully' "$LOG")"
docker exec "$NAME" mysql -uswift -pswift swift_empty -N -e \
  "select 'pays', count(*) from country union all select 'fonctions PEP', count(*) from pep_function union all select 'parsers', count(*) from parser union all select 'liens', count(*) from pep_function_parser" 2>/dev/null
grep -E 'APPLICATION FAILED|Caused by' "$LOG" | head -5
docker rm -f "$NAME" >/dev/null 2>&1
[ $STATUS -eq 0 ] && echo "OK : l'application démarre sur une base vide." || echo "ÉCHEC : voir $LOG"
exit $STATUS
