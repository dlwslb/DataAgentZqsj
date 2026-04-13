#!/bin/bash

# 项目地址
LocalBackDir=/data/agent

for i in `ls -d $LocalBackDir`;
do
id=`cat $i/id`
docker stop $id;
docker rm $id;
done