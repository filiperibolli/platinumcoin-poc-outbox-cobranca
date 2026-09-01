# A aplicação como container, para que os oito passos e as cinco fontes possam
# ser observados com `docker compose logs -f app` — um lugar só, junto com os
# logs do Postgres, do LocalStack e do parceiro SFTP.
#
# Duas etapas porque a imagem final não precisa do Maven nem do código-fonte:
# o que roda é o jar executável que o spring-boot-maven-plugin empacota.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /projeto

# O pom sozinho primeiro: enquanto as dependências não mudarem, esta camada é
# reaproveitada e o build seguinte não volta à rede.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
# `spring-boot:repackage` chamado aqui, e não ligado ao `package` no pom: o
# artefato do projeto continua sendo um jar comum — quem precisa do jar
# executável é esta imagem, e a decisão do pom não muda por causa dela.
# Sem testes: a suíte sobe os próprios containers via Testcontainers, e não há
# daemon Docker dentro do build. `mvn test` continua sendo o lugar dela.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package spring-boot:repackage -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /projeto/target/ciclo-de-cobranca-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
