#! /bin/sh

cd /data/agent

docker build -t sjmsagent .

docker run --restart=always --privileged=true -d \
 -v /run/docker.sock:/var/run/docker.sock \
 --name sjmsagent \
 -p 8065:8065 \
 sjmsagent > /data/agent/id