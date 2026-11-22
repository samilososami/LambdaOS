/* Lambda experimental WPA PMK path. Uses OpenSSL's ARM SHA dispatch. */
#ifndef LAMBDA_PMK_SHA1_H
#define LAMBDA_PMK_SHA1_H
#include <openssl/sha.h>
#include <stdint.h>
#include <string.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>
static void lambda_be32(unsigned char *p,uint32_t x) {p[0]=x>>24;p[1]=x>>16;p[2]=x>>8;p[3]=x;}
static void lambda_state(SHA_CTX *c,const SHA_CTX *s) {c->h0=s->h0;c->h1=s->h1;c->h2=s->h2;c->h3=s->h3;c->h4=s->h4;}
static void lambda_digest(const SHA_CTX *c,unsigned char *p) {lambda_be32(p,c->h0);lambda_be32(p+4,c->h1);lambda_be32(p+8,c->h2);lambda_be32(p+12,c->h3);lambda_be32(p+16,c->h4);}
static void lambda_pmk(const unsigned char *pass,size_t length,const unsigned char *ssid,size_t n,unsigned char out[32]) {
    unsigned char pad[64],u[20],v[20],block[64]={0},salt[36];
    SHA_CTX in,outseed,c;
    memset(pad,0x36,64);for(size_t i=0;i<length;i++)pad[i]^=pass[i];
    SHA1_Init(&in);SHA1_Update(&in,pad,64);
    memset(pad,0x5c,64);for(size_t i=0;i<length;i++)pad[i]^=pass[i];
    SHA1_Init(&outseed);SHA1_Update(&outseed,pad,64);
    memcpy(salt,ssid,n);block[20]=0x80;lambda_be32(block+60,84*8);
    for(unsigned part=1;part<=2;part++) {
        lambda_be32(salt+n,part);c=in;SHA1_Update(&c,salt,n+4);SHA1_Final(u,&c);
        c=outseed;SHA1_Update(&c,u,20);SHA1_Final(u,&c);memcpy(v,u,20);
        for(unsigned j=1;j<4096;j++) {
            memcpy(block,u,20);lambda_state(&c,&in);SHA1_Transform(&c,block);lambda_digest(&c,block);
            lambda_state(&c,&outseed);SHA1_Transform(&c,block);lambda_digest(&c,u);
            for(int k=0;k<20;k++)v[k]^=u[k];
        }
        memcpy(out+(part-1)*20,v,part==1?20:12);
    }
}
#endif
