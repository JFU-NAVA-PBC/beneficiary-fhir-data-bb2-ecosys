# `bfd-server-load` Node

## Overview

The files within this directory comprise of the Python source code executed by each node Lambda, the
Docker files used to build the node Lambda Docker image, and the Jenkinsfile and related build
script for the `bfd-server-load-build` Jenkins pipeline.

## Building the `bfd-mgmt-server-load-node` Docker Image

### Building the Image Locally

As the `Dockerfile` for this image is not at the root of this `locust_tests` project, we need to
ensure the [Docker build
context](https://docs.docker.com/engine/reference/commandline/build/#description) is properly set to
the root. This can be achieved in two ways:

#### Option 1 -- Specifying the `services/server-load/node/node.Dockerfile` from the Root

This option is _preferred_ to option 2, as it is generally better form to run `docker build` from
the directory of the build context itself.

Ensure you current working directory is `/apps/utils/locust_tests` and run:

```bash
docker build -f services/server-load/node/node.Dockerfile -t "<your-tag>" --platform linux/amd64 .
```

#### Option 2 -- Specifying the Context Relative to This Directory

This option simply tells Docker that the build context is the grandparent directory, via
`../../../`. Ensure your current working directory is `services/server-load/node` and run:

```bash
docker build -t "<your-tag>" --platform linux/amd64 ../../../.
```