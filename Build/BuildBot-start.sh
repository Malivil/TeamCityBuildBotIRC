#!/bin/bash

nohup java -jar BuildBot.jar < /dev/null >> /dev/null 2>> /dev/null &
echo "$!" > BuildBot.pid