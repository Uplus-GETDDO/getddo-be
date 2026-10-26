#!/usr/bin/env bash
# Stage tar만 준비한다. 검증 실패 시 env/current/컨테이너는 변경하지 않는다.
set -Eeuo pipefail
[[ ${GETDDO_DEPLOY_ENVIRONMENT:-} == staging ]]
source_sha=${1:?source SHA required}
[[ $source_sha =~ ^[a-f0-9]{40}$ ]]
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
sha256sum --check image.tar.gz.sha256
image=$(cat image-id.txt)
[[ $image =~ ^sha256:[a-f0-9]{64}$ ]]
timeout 300 docker image load --input image.tar.gz
[[ $(timeout 10 docker image inspect --format '{{.Id}}' "getddo-be:$source_sha") == "$image" ]]
[[ $(timeout 10 docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image") == "$source_sha" ]]
printf '%s\n' "$image" > image-ref.txt
printf '%s\n' "$source_sha" > source-sha.txt
