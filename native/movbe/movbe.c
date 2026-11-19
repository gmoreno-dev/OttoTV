/*
 * MOVBE for processors that don't have it.
 *
 * MOVBE (load or store with the bytes reversed) came to Intel's desktop
 * and laptop chips with Haswell (2013). The Widevine DRM service upstream
 * ships was built for chips that have it: on an older one (the Acer's Ivy
 * Bridge) it dies with SIGILL the first time an app asks for a license, and
 * no app with protected video plays. It uses MOVBE in 7 places, all in
 * building requests (none in decrypting video), so doing those few in
 * software costs nothing noticeable.
 *
 * Loaded into that service with LD_PRELOAD (iso/scripts/9-otto,
 * otto_movbe), only on CPUs without MOVBE. A SIGILL handler looks at the
 * faulting instruction: when it is a MOVBE, it does what the CPU would have
 * done and carries on after it. Anything else goes to the handler that was
 * there before (Android's crash reporter), as if this weren't loaded.
 *
 * Freestanding: no libc, so the same file works with Android's and in the
 * tests (tests/movbe). Raw syscalls only.
 */
#include <stdint.h>

#define OTTO_SIGILL 4
#define OTTO_SA_SIGINFO 0x00000004UL
#define OTTO_SA_RESTORER 0x04000000UL
#define OTTO_SYS_RT_SIGACTION 13

/* struct sigaction as the kernel takes it on x86_64. */
struct otto_ksigaction {
	void *handler;
	unsigned long flags;
	void (*restorer)(void);
	uint64_t mask;
};

static long otto_syscall4(long n, long a, long b, long c, long d)
{
	long ret;
	register long r10 __asm__("r10") = d;
	__asm__ volatile("syscall"
			 : "=a"(ret)
			 : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10)
			 : "rcx", "r11", "memory");
	return ret;
}

/* Returning from a handler: the kernel needs this (rt_sigreturn, 15). */
void otto_movbe_restorer(void);
__asm__(".text\n"
	".global otto_movbe_restorer\n"
	".hidden otto_movbe_restorer\n"
	".type otto_movbe_restorer,@function\n"
	"otto_movbe_restorer:\n"
	"\tmovq $15, %rax\n"
	"\tsyscall\n"
	"\thlt\n");

/*
 * The saved registers in the signal's ucontext, as Linux lays them out on
 * x86_64 (struct sigcontext): r8..r15, rdi, rsi, rbp, rbx, rdx, rax, rcx,
 * rsp, rip. GREG[n] is where register number n (as instructions encode it:
 * rax, rcx, rdx, rbx, rsp, rbp, rsi, rdi, r8..r15) is kept.
 */
#define OTTO_UC_GREGS 40 /* uc_flags, uc_link, uc_stack */
#define OTTO_RIP 16
static const int GREG[16] = {13, 14, 12, 11, 15, 10, 9, 8, 0, 1, 2, 3, 4, 5, 6, 7};

static int32_t otto_rd32(const uint8_t *p)
{
	return (int32_t)((uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24);
}

/*
 * Carries out the MOVBE at gregs[RIP], if that is what it is, and moves RIP
 * past it. Returns 1 when it did, 0 when the instruction is something else
 * (nothing is changed then). Exported for the tests.
 */
__attribute__((visibility("default"))) int otto_movbe_emulate(uint64_t *g)
{
	const uint8_t *p = (const uint8_t *)(uintptr_t)g[OTTO_RIP];
	int size = 4, rex = 0, store, mod, reg, rm;
	uint64_t addr = 0;
	uint8_t modrm;

	if (*p == 0x66) { /* operand size: 16 bits */
		size = 2;
		p++;
	}
	if ((*p & 0xF0) == 0x40) { /* REX */
		rex = *p;
		p++;
	}
	if (rex & 8) /* REX.W: 64 bits */
		size = 8;
	/* 0F 38 F0: MOVBE reg, mem. 0F 38 F1: MOVBE mem, reg. (With an F2
	 * prefix the same bytes are CRC32, which older CPUs do have: the F2
	 * never gets this far.) */
	if (p[0] != 0x0F || p[1] != 0x38 || (p[2] != 0xF0 && p[2] != 0xF1))
		return 0;
	store = p[2] == 0xF1;
	p += 3;

	modrm = *p++;
	mod = modrm >> 6;
	reg = ((modrm >> 3) & 7) | ((rex & 4) << 1);
	rm = modrm & 7;
	if (mod == 3) /* MOVBE always has a memory operand */
		return 0;
	if (rm == 4) { /* SIB byte */
		uint8_t sib = *p++;
		int index = ((sib >> 3) & 7) | ((rex & 2) << 2);
		int base = (sib & 7) | ((rex & 1) << 3);
		if (index != 4)
			addr += g[GREG[index]] << (sib >> 6);
		if ((sib & 7) == 5 && mod == 0) {
			addr += (uint64_t)(int64_t)otto_rd32(p);
			p += 4;
		} else {
			addr += g[GREG[base]];
		}
	} else if (rm == 5 && mod == 0) { /* RIP-relative: from the next instruction */
		int32_t disp = otto_rd32(p);
		p += 4;
		addr = (uint64_t)(uintptr_t)p + (uint64_t)(int64_t)disp;
	} else {
		addr = g[GREG[rm | ((rex & 1) << 3)]];
	}
	if (mod == 1) {
		addr += (uint64_t)(int64_t)(int8_t)*p;
		p++;
	} else if (mod == 2) {
		addr += (uint64_t)(int64_t)otto_rd32(p);
		p += 4;
	}

	volatile uint8_t *m = (volatile uint8_t *)(uintptr_t)addr;
	uint64_t *r = &g[GREG[reg]];
	int i;
	if (store) {
		uint64_t v = *r;
		for (i = size - 1; i >= 0; i--) { /* most significant byte first */
			m[i] = (uint8_t)v;
			v >>= 8;
		}
	} else {
		uint64_t v = 0;
		for (i = 0; i < size; i++)
			v = v << 8 | m[i];
		if (size == 2)
			*r = (*r & ~(uint64_t)0xFFFF) | v; /* like MOV r16: the rest stays */
		else
			*r = v; /* a 32-bit result clears the upper half, like MOV r32 */
	}
	g[OTTO_RIP] = (uint64_t)(uintptr_t)p;
	return 1;
}

static struct otto_ksigaction otto_previous;

static void otto_movbe_handler(int sig, void *info, void *uc)
{
	(void)sig;
	(void)info;
	if (otto_movbe_emulate((uint64_t *)((char *)uc + OTTO_UC_GREGS)))
		return;
	/* Not ours: give the signal back to whoever had it. The instruction
	 * runs again and faults into that handler. */
	otto_syscall4(OTTO_SYS_RT_SIGACTION, OTTO_SIGILL, (long)&otto_previous, 0, 8);
}

__attribute__((constructor)) static void otto_movbe_init(void)
{
	struct otto_ksigaction sa;
	sa.handler = (void *)otto_movbe_handler;
	sa.flags = OTTO_SA_SIGINFO | OTTO_SA_RESTORER;
	sa.restorer = otto_movbe_restorer;
	sa.mask = 0;
	otto_syscall4(OTTO_SYS_RT_SIGACTION, OTTO_SIGILL, (long)&sa, (long)&otto_previous, 8);
}
