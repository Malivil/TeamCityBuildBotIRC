#!/bin/bash
# Kills a process whose PID is stored in the BuildBot.pid file

kill `cat BuildBot.pid`
