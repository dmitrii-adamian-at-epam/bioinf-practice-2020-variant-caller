package com.epam.bioinf.variantcaller.caller;

import com.epam.bioinf.variantcaller.helpers.ProgressBar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMTag;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class Caller {
  private final IndexedFastaSequenceFile fastaSequenceFile;
  private final List<SAMRecord> samRecords;
  private final List<VariantInfo> variantInfoList;

  public Caller(IndexedFastaSequenceFile fastaSequenceFile, List<SAMRecord> samRecords) {
    this.fastaSequenceFile = fastaSequenceFile;
    this.samRecords = samRecords;
    this.variantInfoList = new ArrayList<>();
  }

  public List<VariantContext> findVariants() {
    callVariants();
    var result = variantInfoList
        .stream()
        .map(VariantInfo::makeVariantContext)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    result.forEach(el -> System.out.println(el.toString()));
    return result;
  }

  private void callVariants() {
    ProgressBar progressBar = new ProgressBar(samRecords.size(), System.out);
    samRecords.forEach(samRecord -> {
      processSingleRead(samRecord);
      progressBar.incrementProgress();
    });
  }

  private void processSingleRead(SAMRecord samRecord) {
    if (samRecord.getContig() == null) return;
    String subsequenceBaseString = fastaSequenceFile
        .getSubsequenceAt(samRecord.getContig(), samRecord.getStart(), samRecord.getEnd())
        .getBaseString();
    String readBaseString = samRecord.getReadString();
    String sampleName = samRecord.getAttribute(SAMTag.SM.name()).toString();
    ReadData readData = new ReadData(
        subsequenceBaseString,
        readBaseString,
        sampleName,
        samRecord.getContig(),
        samRecord.getStart(),
        samRecord.getReadNegativeStrandFlag()
    );
    IndexCounter indexCounter = new IndexCounter(0, 0);
    for (CigarElement cigarElement : samRecord.getCigar().getCigarElements()) {
      processSingleCigarElement(cigarElement, readData, indexCounter);
    }
  }

  private void processSingleCigarElement(
      CigarElement cigarElement,
      ReadData readData,
      IndexCounter indexCounter
  ) {
    // Information about CIGAR operators
    // https://javadoc.io/doc/com.github.samtools/htsjdk/2.13.1/htsjdk/samtools/CigarOperator.html
    CigarOperator operator = cigarElement.getOperator();
    int cigarElementLength = cigarElement.getLength();
    switch (operator) {
      case H:
      case P:
        break;
      case S:
        indexCounter.moveReadIndex(cigarElementLength);
        break;
      case N:
      case I: {
        var alleles = performInsertionOperation(cigarElement.getLength(), readData, indexCounter);
        saveAlleles(alleles, readData, indexCounter.getRefIndex());
        indexCounter.moveReadIndex(cigarElementLength);
        break;
      }
      case D: {
        var alleles = performDeletionOperation(cigarElement.getLength(), readData, indexCounter);
        saveAlleles(alleles, readData, indexCounter.getRefIndex());
        indexCounter.moveRefIndex(cigarElementLength);
        break;
      }
      case M:
      case X:
      case EQ: {
        for (int i = 0; i < cigarElement.getLength(); ++i) {
          var alleles = performAlignmentCigarOperation(readData, indexCounter, i);
          saveAlleles(alleles, readData, indexCounter.getRefIndex() + i);
        }
        indexCounter.moveReadIndex(cigarElementLength);
        indexCounter.moveRefIndex(cigarElementLength);
        break;
      }
    }
  }

  private Alleles performDeletionOperation(
      int cigarElementLength,
      ReadData readData,
      IndexCounter indexCounter
  ) {
    char refChar = readData.getSubsequenceBaseString().charAt(indexCounter.getRefIndex());
    Allele refAllele = Allele.create(
        getIndelAlleleString(
            cigarElementLength,
            refChar,
            readData.getReadBaseString(),
            indexCounter.getReadIndex()
        ),
        true
    );
    Allele altAllele = Allele.create(String.valueOf(refChar), false);
    return new Alleles(refAllele, altAllele);
  }

  private Alleles performInsertionOperation(
      int cigarElementLength,
      ReadData readData,
      IndexCounter indexCounter
  ) {
    char refChar = readData.getSubsequenceBaseString().charAt(indexCounter.getRefIndex());
    Allele refAllele = Allele.create(String.valueOf(refChar), true);
    Allele altAllele = Allele.create(getIndelAlleleString(
        cigarElementLength,
        refChar,
        readData.getReadBaseString(),
        indexCounter.getReadIndex()
    ), false);
    return new Alleles(refAllele, altAllele);
  }

  private Alleles performAlignmentCigarOperation(
      ReadData readData,
      IndexCounter indexCounter,
      int shift
  ) {
    Allele refAllele = Allele.create(
        String.valueOf(
            readData.getSubsequenceBaseString().charAt(indexCounter.getRefIndex() + shift)
        ),
        true
    );
    Allele altAllele = Allele.create(
        String.valueOf(
            readData.getReadBaseString().charAt(indexCounter.getReadIndex() + shift)
        ),
        false
    );
    return new Alleles(refAllele, altAllele);
  }

  private String getIndelAlleleString(int cigarElementLength,
                                      char refChar,
                                      String baseString,
                                      int startIndex
  ) {
    return refChar + baseString.substring(
        startIndex,
        startIndex + cigarElementLength
    );
  }

  private void saveAlleles(Alleles alleles, ReadData readData, int shift) {
    computeContext
        (
            readData.getContig(),
            readData.getStart() + shift,
            alleles.getRefAllele()
        )
        .computeSample(readData.getSampleName())
        .computeAllele(alleles.getAltAllele())
        .incrementStrandCount(readData.getReadNegativeStrandFlag());
  }

  private VariantInfo computeContext(String contig, int pos, Allele ref) {
    Optional<VariantInfo> context = variantInfoList
        .stream()
        .filter(
            ctx -> ctx.getContig().equals(contig)
                && ctx.getPos() == pos
                && ctx.getRefAllele().compareTo(ref) == 0
        )
        .findFirst();
    if (context.isPresent()) {
      return context.get();
    } else {
      VariantInfo variantInfo = new VariantInfo(contig, pos, ref);
      variantInfoList.add(variantInfo);
      return variantInfo;
    }
  }
}
