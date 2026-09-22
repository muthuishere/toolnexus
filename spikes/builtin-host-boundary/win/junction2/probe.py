import os,sys
p=sys.argv[1]
print("python: abspath=%s realpath=%s samefile_ok=%s" % (os.path.abspath(p), os.path.realpath(p), os.path.normcase(os.path.realpath(p))))
