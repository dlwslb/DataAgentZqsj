#! /bin/sh

cd /data/agent

docker build -t sjmsagent .

docker run --restart=always --privileged=true -d \
 -v /run/docker.sock:/var/run/docker.sock \
 --name sjmsagent \
 -p 58065:58065 \
 -e SPRING_PROFILES_ACTIVE=prod \
 sjmsagent > /data/agent/id