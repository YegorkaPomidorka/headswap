# Реле бокса smartBox (AK3918). Две цели:
#   make arm    — статическая сборка под ARM EABI5 soft-float (боевая, для бокса)
#   make native — локальная сборка хоста для smoke-теста (test_relay.py)
#
# qemu-прогон ARM-бинаря: qemu-arm ./relay-arm  (см. README.md).

ARMCC   ?= arm-linux-gnueabi-gcc
CFLAGS  := -O2 -Wall -Wextra -pthread
LDFLAGS := -pthread

.PHONY: all arm native clean
all: arm native

arm: relay-arm
relay-arm: relay.c
	$(ARMCC) $(CFLAGS) -static -o $@ $< $(LDFLAGS)

native: relay
relay: relay.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

clean:
	rm -f relay relay-arm
