#!/usr/bin/env bash
set -e
rm -rf build

HERE=contracts/openzeppelin
BUILDDIR=$HERE/build
CONTRACTSDIR=$HERE/src/main/solidity
TESTCONTRACTSDIR=$HERE/src/test/solidity
OUTPUTDIR=$HERE/src/main/java
TESTOUTPUTDIR=$HERE/src/test/java
PACKAGE=net.consensys.htlcbridge.openzeppelin.soliditywrappers
WEB3J=../web3j-abi/codegen/build/install/codegen/bin/codegen


# compiling one file also compiles its dependendencies. We use overwrite to avoid the related warnings.
solc $CONTRACTSDIR/token/ERC20/presets/ERC20PresetFixedSupply.sol --allow-paths . --bin --abi --optimize -o $BUILDDIR --overwrite
solc $CONTRACTSDIR/proxy/transparent/ProxyAdmin.sol --allow-paths . --bin --abi --optimize -o $BUILDDIR --overwrite
solc $CONTRACTSDIR/proxy/transparent/TransparentUpgradeableProxy.sol --allow-paths . --bin --abi --optimize -o $BUILDDIR --overwrite

$WEB3J solidity generate -a=$BUILDDIR/ERC20PresetFixedSupply.abi -b=$BUILDDIR/ERC20PresetFixedSupply.bin -o=$OUTPUTDIR -p=$PACKAGE
$WEB3J solidity generate -a=$BUILDDIR/ProxyAdmin.abi -b=$BUILDDIR/ProxyAdmin.bin -o=$OUTPUTDIR -p=$PACKAGE
$WEB3J solidity generate -a=$BUILDDIR/TransparentUpgradeableProxy.abi -b=$BUILDDIR/TransparentUpgradeableProxy.bin -o=$OUTPUTDIR -p=$PACKAGE


