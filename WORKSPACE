load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

# TODO Oct 15: future stuff for bazel rules will apparently be under
# https://github.com/bazelbuild/rules_kotlin/tree/master but using their example at the moment doesn't work - the zip
# is 404ing there versus here

rules_kotlin_version = "legacy-modded-1_0_0-01"

rules_kotlin_sha = "b7984b28e0a1e010e225a3ecdf0f49588b7b9365640af783bd01256585cbb3ae"

http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = rules_kotlin_sha,
    strip_prefix = "rules_kotlin-%s" % rules_kotlin_version,
    type = "zip",
    urls = ["https://github.com/cgruber/rules_kotlin/archive/%s.zip" % rules_kotlin_version],
)

load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kotlin_repositories", "kt_register_toolchains")

kotlin_repositories()  # if you want the default. Otherwise see custom kotlinc distribution below

kt_register_toolchains()  # to use the default toolchain, otherwise see toolchains below
