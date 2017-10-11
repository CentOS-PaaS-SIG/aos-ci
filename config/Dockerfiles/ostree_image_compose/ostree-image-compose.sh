#!/usr/bin/sh

set -xeuo pipefail

# A simple shell script to automate v2c converstion
# using Image Factory in a container

# argument 1: Path to file containing the image to be converted

# Factory defaults to wanting a root PW in the TDL - this causes
# problems with converted images - just force it
# TODO: Point the working directories at the bind mounted location?

base_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Start libvirtd
ls /var/run/libvirt
mkdir -p /var/run/libvirt
libvirtd &
sleep 5
virtlogd &

chmod 666 /dev/kvm

function clean_up {
  set +e
  kill $(jobs -p)
  for screenshot in /var/lib/oz/screenshots/*.ppm; do
      [ -e "$screenshot" ] && cp $screenshot /home/output/logs
  done
}
trap clean_up EXIT SIGHUP SIGINT SIGTERM

# Do our thing
if [ "${branch}" = "rawhide" ]; then
    VERSION="rawhide"
else
    VERSION=$(echo $branch | sed -e 's/[a-zA-Z]*//')
fi

REF="fedora/${branch}/x86_64/atomic-host"

mkdir -p /home/output/logs

imgdir=/var/lib/imagefactory/storage/

version=$(ostree --repo=/home/output/ostree show --print-metadata-key=version $REF| sed -e "s/'//g")
release=$(ostree --repo=/home/output/ostree rev-parse $REF| cut -c -15)

if [ -d "/home/output/images" ]; then
    for image in /home/output/images/fedora-atomic-*.qcow2; do
        if [ -e "$image" ]; then
            # Find the last image we pushed
            prev_img=$(ls -tr /home/output/images/fedora-atomic-*.qcow2 | tail -n 1)
            prev_rel=$(echo $prev_img | sed -e 's/.*-\([^-]*\).qcow2/\1/')
            # Don't fail if the previous build has been pruned
            (rpm-ostree db --repo=/home/output/ostree diff $prev_rel $release || echo "Previous build has been pruned") | tee /home/output/logs/packages.txt
        fi
        break
    done
else
    mkdir /home/output/images
fi

pushd /home/output/ostree
python -m SimpleHTTPServer &
popd

# Grab the kickstart file from fedora upstream
curl -o /home/output/logs/fedora-atomic.ks https://pagure.io/fedora-kickstarts/raw/${branch}/f/fedora-atomic.ks
#cp $base_dir/config/ostree/fedora-atomic-${branch}.ks /home/output/logs/fedora-atomic.ks

# Put new url into the kickstart file
sed -i "s|^ostreesetup.*|ostreesetup --nogpg --osname=fedora-atomic --remote=fedora-atomic --url=http://192.168.122.1:8000/ --ref=$REF|" /home/output/logs/fedora-atomic.ks

# point to upstream
sed -i "s|\(%end.*$\)|ostree remote delete fedora-atomic\nostree remote add --set=gpg-verify=false fedora-atomic ${HTTP_BASE}/${branch}/ostree\n\1|" /home/output/logs/fedora-atomic.ks

# Remove ostree refs create form upstream kickstart
sed -i "s|^ostree refs.*||" /home/output/logs/fedora-atomic.ks
sed -i "s|^ostree admin set-origin.*||" /home/output/logs/fedora-atomic.ks

# Pull down Fedora net install image if needed
if [ ! -e "/home/output/netinst" ]; then
    mkdir -p /home/output/netinst
fi

pushd /home/output/netinst
# First try and download iso from development
wget -c -r -nd -A iso --accept-regex "Fedora-Everything-netinst-.*\.iso" "http://dl.fedoraproject.org/pub/fedora/linux/development/${VERSION}/Everything/x86_64/iso/" || true
# If unable to download from development then try downloading from releases
wget -c -r -nd -A iso --accept-regex "Fedora-Everything-netinst-.*\.iso" "http://dl.fedoraproject.org/pub/fedora/linux/releases/${VERSION}/Everything/x86_64/iso/" || true

latest=$(ls --hide Fedora-Everything-netinst-x86_64.iso | tail -n 1)
if [ -n "$latest" ]; then
    ln -sf $latest Fedora-Everything-netinst-x86_64.iso
fi
popd

# Create a tdl file for imagefactory
#       <install type='url'>
#           <url>http://download.fedoraproject.org/pub/fedora/linux/releases/25/Everything/x86_64/os/</url>
#       </install>
cat <<EOF >/home/output/logs/fedora-${branch}.tdl
<template>
    <name>${branch}</name>
    <os>
        <name>Fedora</name>
        <version>${VERSION}</version>
        <arch>x86_64</arch>
        <install type='iso'>
            <iso>file:///home/output/netinst/Fedora-Everything-netinst-x86_64.iso</iso>
        </install>
        <rootpw>password</rootpw>
        <kernelparam>console=ttyS0</kernelparam>
    </os>
</template>
EOF

#export LIBGUESTFS_BACKEND=direct

imagefactory --debug --imgdir $imgdir --timeout 3000 base_image /home/output/logs/fedora-${branch}.tdl --parameter offline_icicle true --file-parameter install_script /home/output/logs/fedora-atomic.ks

# convert to qcow
imgname="fedora-atomic-$version-$release"
qemu-img convert -c -p -O qcow2 $imgdir/*body /home/output/images/$imgname.qcow2

# Record the commit so we can test it later
commit=$(ostree --repo=/home/output/ostree rev-parse ${REF})
cat << EOF > /home/output/logs/ostree.props
builtcommit=$commit
image2boot=${HTTP_BASE}/${branch}/images/$imgname.qcow2
image_name=$imgname.qcow2
EOF

# Cleanup older qcow2 images
pushd /home/output/images || exit 1
latest=""
if [ -e "latest-atomic.qcow2" ]; then
    latest=$(readlink latest-atomic.qcow2)
fi

# delete images over 3 days old but don't delete what our latest link points to
find . -type f -mtime +3 ! -name "$latest" -exec rm -v {} \;
popd
