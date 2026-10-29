# Lamb research checkpoint

Checkpoint date: 29 October 2026. Development is paused while LambdaOS and its
system interfaces are defined. The existing lab, model references, evaluation
results and source history are retained locally.

The local checkpoint tag is `lamb-paused-20261029`, based on implementation
commit `403fb10` and the subsequent pause record. A source/runtime snapshot was
verified across **133 files**; a recoverable Git bundle is also retained. This
public summary does not include device identifiers, credentials or private logs.

| Reference | State |
|---|---|
| Gemma E2B official mobile artifact | Preserved; 2,588,147,712 bytes |
| Official artifact SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Untrained export control | Converted; three basic host inference checks passed |
| Export control size | 5,071,837,136 bytes |
| Export control SHA-256 | `75ef64add3be1e4a53063a6acc5d150d0d036590bdaf423d801ff703bb5b478f` |
| Control recipe | INT8 weights / FP32 activations, external embeddings, text only |
| Control conversion duration | 664.22 seconds on the build PC |
| Control tested on the S10 | Not yet |
| Fine-tuning / distillation | Not started |

The control export does not reproduce the official Mobile QAT recipe. Its host
smoke checks establish that it loads and generates text; they do not establish
equivalent quality, mobile memory use or speed. The existing phone model was
not replaced.

When work resumes, compare the source, export and official reference under
controlled conditions before training. Future training examples should reflect
the actual LambdaOS command contracts and verified device capabilities.
