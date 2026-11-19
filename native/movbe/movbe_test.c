/*
 * Tests for movbe.c, run by tests/run.sh twice: on this machine as it is,
 * and in QEMU as an Ivy Bridge, which has no MOVBE: there every MOVBE below
 * faults and movbe.c does it instead. Same answers both times, or it fails.
 *
 * Also checks the decoder directly, on encodings run through no CPU, and
 * that an illegal instruction that isn't a MOVBE still kills the process.
 */
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

int otto_movbe_emulate(uint64_t *gregs);

static int failures;

#define CHECK(what, got, want)                                                              \
	do {                                                                                \
		unsigned long long g_ = (unsigned long long)(got), w_ = (unsigned long long)(want); \
		if (g_ != w_) {                                                             \
			printf("FAIL %s: got %#llx, want %#llx\n", what, g_, w_);           \
			failures++;                                                         \
		}                                                                           \
	} while (0)

static const uint32_t rip_data = 0x11223344;

/* Four bytes read most significant first, as MOVBE stores them. */
static uint32_t be32(const uint8_t *p)
{
	return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}

/* Through the CPU, or through movbe.c when the CPU can't. */
static void through_the_cpu(void)
{
	uint8_t buf[0x200];
	uint64_t rax;
	memset(buf, 0, sizeof buf);

	/* Pack_uint32_t's: base + index, 32 bits. */
	__asm__ volatile("movbe %%eax, (%%rcx,%%rdx,1)" ::"a"(0xAABBCCDD), "c"(buf), "d"(4) : "memory");
	CHECK("store32 base+index", be32(buf + 4), 0xAABBCCDD); /* most significant byte first */

	/* Pack_uint16_t's: 66 prefix. */
	__asm__ volatile("movbe %%ax, (%%rcx)" ::"a"(0x1234), "c"(buf + 16) : "memory");
	CHECK("store16", (uint32_t)buf[16] << 8 | buf[17], 0x1234);

	/* 64 bits (REX.W), with a disp8. */
	__asm__ volatile("movbe %%rax, 8(%%rcx)" ::"a"(0x0102030405060708ULL), "c"(buf + 32) : "memory");
	CHECK("store64 disp8", buf[40], 0x01);
	CHECK("store64 disp8 last", buf[47], 0x08);

	/* A REX register (r14d) into memory, as Pack_uint64_t does. */
	__asm__ volatile("movl $0x0A0B0C0D, %%r14d\n\tmovbe %%r14d, (%%rcx)" ::"c"(buf + 64) : "r14", "memory");
	CHECK("store r14d", be32(buf + 64), 0x0A0B0C0D);

	/* Loads: 32 bits clear the upper half... */
	buf[96] = 0x12, buf[97] = 0x34, buf[98] = 0x56, buf[99] = 0x78;
	rax = 0xFFFFFFFFFFFFFFFFULL;
	__asm__ volatile("movbe (%%rcx), %%eax" : "+a"(rax) : "c"(buf + 96) : "memory");
	CHECK("load32 zero-extends", rax, 0x12345678);

	/* ...16 bits keep it. */
	rax = 0xFFFFFFFFFFFF0000ULL;
	__asm__ volatile("movbe (%%rcx), %%ax" : "+a"(rax) : "c"(buf + 96) : "memory");
	CHECK("load16 keeps the rest", rax, 0xFFFFFFFFFFFF1234ULL);

	/* initDataResemblesPSSH's: load into ecx from (r14). */
	{
		uint64_t out;
		__asm__ volatile("movq %1, %%r14\n\tmovbe (%%r14), %%ecx\n\tmovq %%rcx, %0"
				 : "=r"(out)
				 : "r"(buf + 96)
				 : "r14", "rcx", "memory");
		CHECK("load (r14) into ecx", out, 0x12345678);
	}

	/* r12 as the base needs a SIB byte, and a disp32. */
	{
		uint64_t out;
		buf[0x180] = 1, buf[0x181] = 2, buf[0x182] = 3, buf[0x183] = 4;
		buf[0x184] = 5, buf[0x185] = 6, buf[0x186] = 7, buf[0x187] = 8;
		__asm__ volatile("movq %1, %%r12\n\tmovbe 0x180(%%r12), %%r13\n\tmovq %%r13, %0"
				 : "=r"(out)
				 : "r"(buf)
				 : "r12", "r13", "memory");
		CHECK("load64 r12+disp32 into r13", out, 0x0102030405060708ULL);
	}

	/* RIP-relative. */
	{
		uint32_t out;
		__asm__ volatile("movbe %1, %0" : "=r"(out) : "m"(rip_data));
		CHECK("load RIP-relative", out, 0x44332211);
	}
}

/* The decoder on its own, with registers made up. */
static void decoder(void)
{
	uint64_t g[23];
	uint8_t mem[64];
	memset(mem, 0, sizeof mem);

	/* movbe %ebp,(%rcx,%rax,1): 0f 38 f1 2c 01, as in Pack_uint32_t */
	static const uint8_t pack32[] = {0x0f, 0x38, 0xf1, 0x2c, 0x01, 0x90};
	memset(g, 0, sizeof g);
	g[16] = (uint64_t)(uintptr_t)pack32;
	g[14] = (uint64_t)(uintptr_t)mem; /* rcx */
	g[13] = 8;                        /* rax */
	g[10] = 0xCAFEBABE;               /* rbp */
	CHECK("decode Pack_uint32_t", otto_movbe_emulate(g), 1);
	CHECK("decode Pack_uint32_t bytes", be32(mem + 8), 0xCAFEBABE);
	CHECK("decode Pack_uint32_t RIP", g[16], (uint64_t)(uintptr_t)(pack32 + 5));

	/* movbe %r14d,(%rcx,%rax,1): 44 0f 38 f1 34 01 */
	static const uint8_t pack64[] = {0x44, 0x0f, 0x38, 0xf1, 0x34, 0x01};
	memset(g, 0, sizeof g);
	g[16] = (uint64_t)(uintptr_t)pack64;
	g[14] = (uint64_t)(uintptr_t)mem;
	g[13] = 20;
	g[6] = 0x01020304; /* r14 */
	CHECK("decode REX.R", otto_movbe_emulate(g), 1);
	CHECK("decode REX.R bytes", be32(mem + 20), 0x01020304);
	CHECK("decode REX.R RIP", g[16], (uint64_t)(uintptr_t)(pack64 + 6));

	/* Not MOVBE: ud2, crc32 (f2 0f 38 f1 c1), a register operand
	 * (0f 38 f0 c1 doesn't exist), an fs: prefix. Nothing changes. */
	static const uint8_t ud2[] = {0x0f, 0x0b};
	static const uint8_t crc32[] = {0xf2, 0x0f, 0x38, 0xf1, 0xc1};
	static const uint8_t regop[] = {0x0f, 0x38, 0xf0, 0xc1};
	static const uint8_t fs[] = {0x64, 0x0f, 0x38, 0xf0, 0x01};
	const uint8_t *others[] = {ud2, crc32, regop, fs};
	for (int i = 0; i < 4; i++) {
		memset(g, 0, sizeof g);
		g[16] = (uint64_t)(uintptr_t)others[i];
		CHECK("not a MOVBE", otto_movbe_emulate(g), 0);
		CHECK("not a MOVBE: RIP kept", g[16], (uint64_t)(uintptr_t)others[i]);
	}
}

/* Any other illegal instruction still kills the process, by SIGILL. */
static void other_sigill(void)
{
	pid_t pid = fork();
	if (pid == 0) {
		__asm__ volatile("ud2");
		_exit(0);
	}
	int status = 0;
	waitpid(pid, &status, 0);
	CHECK("ud2 still dies of SIGILL", WIFSIGNALED(status) ? WTERMSIG(status) : -1, SIGILL);
}

int main(void)
{
	through_the_cpu();
	decoder();
	other_sigill();
	if (failures) {
		printf("%d failure(s)\n", failures);
		return 1;
	}
	printf("movbe: all good\n");
	return 0;
}
