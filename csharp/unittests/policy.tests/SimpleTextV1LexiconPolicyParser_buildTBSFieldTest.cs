﻿using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using FluentAssertions;
using Health.Direct.Policy.Impl;
using Health.Direct.Policy.X509;
using Xunit;

namespace Health.Direct.Policy.Tests
{
    public class SimpleTextV1LexiconPolicyParser_buildTBSFieldTest
    {
        [Fact]
        public void TestBuildTBSField_Version_AssertFieldNotImplemented()
        {
            var parser = new SimpleTextV1LexiconPolicyParser();
            Assert.Throws<PolicyParseException>(() => parser.BuildTBSField("X509.TBS.Version"));
        }

        [Fact]
        public void TestBuildTBSField_SerialNumber_AssertBuilt()
        {
            var parser = new SimpleTextV1LexiconPolicyParser();
            dynamic tbsField = parser.BuildTBSField("X509.TBS.SerialNumber");
            Assert.NotNull(tbsField);
            Assert.Equal(tbsField.GetType(), typeof(SerialNumberAttributeField));
            Assert.True(tbsField.IsRequired());
        }


        [Fact]
        public void TestBuildTBSField_Signature_AssertFieldNotImplemented()
        {
            var parser = new SimpleTextV1LexiconPolicyParser();
            Assert.Throws<PolicyParseException>(() =>parser.BuildTBSField("X509.TBS.Signature"));
            Assert.Throws<PolicyParseException>(() => parser.BuildTBSField("X509.TBS.Signature+"));
        }

        [Fact]
        public void TestBuildTBSField_Issuer_AssertBuilt()
        {
            var parser = new SimpleTextV1LexiconPolicyParser();
            var tbsField = parser.BuildTBSField("X509.TBS.Issuer.CN") as IssuerAttributeField;
            tbsField.Should().NotBeNull();
            tbsField.GetType().Should().Be(typeof (IssuerAttributeField));
            ((IssuerAttributeField) tbsField).GetRDNAttributeFieldId().Should().Be(RDNAttributeIdentifier.COMMON_NAME);
            tbsField.IsRequired().Should().Be(false);
        }

    }
}
