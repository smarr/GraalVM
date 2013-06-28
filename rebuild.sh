./mx.sh clean
./mx.sh --vm server build -p
#./mx.sh trufflejar

# Convenience for myself
if [ -d "../som/libs" ]; then
  cp build/truffle* ../som/libs
fi
