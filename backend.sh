#!/bin/sh

if pgrep -f backend.jar; then
	true
else	
	java -jar /home/tj/Tiny-Tiny-IRC/backend.jar > /home/tj/Tiny-Tiny-IRC/backend.log  2> /home/tj/Tiny-Tiny-IRC/backend.log &
fi
