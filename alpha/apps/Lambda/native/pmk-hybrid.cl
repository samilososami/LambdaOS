// PBKDF2-HMAC-SHA1; printable ASCII passwords 8..15 bytes, SSID 1..32 bytes.
typedef struct {uint ip[5],op[5],u[10],sum[10];} state_t;
void initial(uint h[5]){h[0]=0x67452301U;h[1]=0xefcdab89U;h[2]=0x98badcfeU;h[3]=0x10325476U;h[4]=0xc3d2e1f0U;}
void sha(uint h[5],uint w[16]){
 uint a=h[0],b=h[1],c=h[2],d=h[3],e=h[4];
 #pragma unroll 80
 for(uint i=0;i<80;i++){
  if(i>=16)w[i&15]=rotate(w[(i-3)&15]^w[(i-8)&15]^w[(i-14)&15]^w[i&15],1U);
  uint f,k;if(i<20){f=(b&c)|(~b&d);k=0x5a827999U;}else if(i<40){f=b^c^d;k=0x6ed9eba1U;}else if(i<60){f=(b&c)|(b&d)|(c&d);k=0x8f1bbcdcU;}else{f=b^c^d;k=0xca62c1d6U;}
  uint t=rotate(a,5U)+f+e+k+w[i&15];e=d;d=c;c=rotate(b,30U);b=a;a=t;
 }h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;
}
void outer(uint h[5],uint seed[5]){uint w[16];for(int i=0;i<16;i++)w[i]=i<5?h[i]:0;w[5]=0x80000000U;w[15]=672U;for(int i=0;i<5;i++)h[i]=seed[i];sha(h,w);}
__kernel void init(__global const uchar *passwords,__global state_t *states,uint count,__global const uchar *ssid,uint slen){
 uint id=get_global_id(0);if(id>=count)return;state_t s;uint w[16];uint len=0;while(len<15&&passwords[id*16+len])len++;
 for(int j=0;j<16;j++)w[j]=0x36363636U;for(uint j=0;j<len;j++)w[j/4]^=((uint)passwords[id*16+j])<<(24-8*(j%4));initial(s.ip);sha(s.ip,w);
 for(int j=0;j<16;j++)w[j]=0x5c5c5c5cU;for(uint j=0;j<len;j++)w[j/4]^=((uint)passwords[id*16+j])<<(24-8*(j%4));initial(s.op);sha(s.op,w);
 for(uint part=1;part<=2;part++){
  for(int j=0;j<16;j++)w[j]=0;
  for(uint j=0;j<slen;j++)w[j/4]|=((uint)ssid[j])<<(24-8*(j%4));
  uint pos=slen+3;w[pos/4]|=part<<(24-8*(pos%4));pos++;w[pos/4]|=0x80U<<(24-8*(pos%4));w[15]=(64+slen+4)*8;
  uint h[5];for(int j=0;j<5;j++)h[j]=s.ip[j];sha(h,w);outer(h,s.op);
  for(int j=0;j<5;j++)s.u[(part-1)*5+j]=s.sum[(part-1)*5+j]=h[j];
 }states[id]=s;
}
__kernel void loop(__global state_t *states,uint count,uint rounds){
 uint id=get_global_id(0);if(id>=count)return;state_t s=states[id];
 for(uint r=0;r<rounds;r++)for(int part=0;part<2;part++){uint h[5];for(int j=0;j<5;j++)h[j]=s.u[part*5+j];outer(h,s.ip);outer(h,s.op);for(int j=0;j<5;j++){s.u[part*5+j]=h[j];s.sum[part*5+j]^=h[j];}}
 states[id]=s;
}
