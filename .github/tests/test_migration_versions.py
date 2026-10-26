"""도메인별 Flyway SQL에서 숫자상 같은 버전이 중복되지 않는지 검사한다."""
from pathlib import Path
import unittest

MIGRATIONS = Path(__file__).resolve().parents[2] / 'storage/db/src/main/resources/db/migration'


def duplicate_versions(files):
    """파일 이름의 버전이 겹치면 버전과 두 파일 경로를 함께 반환한다."""
    versions = {}
    duplicates = []
    for file in files:
        prefix = file.name.split('__', 1)[0]
        parts = [int(part) for part in prefix[1:].replace('_', '.').split('.')]
        while len(parts) > 1 and parts[-1] == 0:
            parts.pop()
        version = tuple(parts)
        if version in versions:
            duplicates.append(f'{prefix}: {versions[version]} / {file}')
        else:
            versions[version] = file
    return duplicates


class MigrationVersionTests(unittest.TestCase):
    def test_version_prefixes_are_unique_across_domains(self):
        """앱 초기화 전에 서로 다른 도메인 SQL의 같은 버전 번호를 찾아낸다."""
        files = sorted(MIGRATIONS.rglob('V*__*.sql'))
        self.assertTrue(files, '검사할 마이그레이션 SQL이 없습니다.')
        self.assertFalse(duplicate_versions([file.relative_to(MIGRATIONS) for file in files]))

    def test_equivalent_numeric_versions_are_duplicates(self):
        """선행 0·구분자·후행 0만 다른 버전도 같은 Flyway 버전으로 거부한다."""
        for left, right in [('1', '001'), ('1', '1.0'), ('1_0', '001.000'),
                            ('1.02', '01_002_00'), ('0', '000.0')]:
            with self.subTest(left=left, right=right):
                files = [Path(f'notification/V{left}__a.sql'), Path(f'ticket/V{right}__b.sql')]
                duplicates = duplicate_versions(files)
                self.assertEqual(1, len(duplicates))
                self.assertIn(str(files[0]), duplicates[0])
                self.assertIn(str(files[1]), duplicates[0])

    def test_distinct_numeric_components_remain_distinct(self):
        """서로 다른 숫자 구성요소와 중간 0을 잘못 합치지 않는다."""
        files = [Path(f'V{version}__a.sql') for version in ('1', '1.1', '1.2', '1.10', '1.0.1')]
        self.assertFalse(duplicate_versions(files))


if __name__ == '__main__':
    unittest.main()
