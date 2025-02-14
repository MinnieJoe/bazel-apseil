// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe.serialization;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.BitSet;

class BitSetCodec extends LeafObjectCodec<BitSet> {
  private static final LongArrayCodec DELEGATE = new LongArrayCodec();

  @Override
  public Class<? extends BitSet> getEncodedClass() {
    return BitSet.class;
  }

  @Override
  public void serialize(BitSet obj, CodedOutputStream codedOut) throws IOException {
    long[] data = obj.toLongArray();
    DELEGATE.serialize(data, codedOut);
  }

  @Override
  public BitSet deserialize(CodedInputStream codedIn) throws IOException {
    return BitSet.valueOf(DELEGATE.deserialize(codedIn));
  }
}
