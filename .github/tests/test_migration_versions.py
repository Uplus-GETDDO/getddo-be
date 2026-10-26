"""도메인별 디렉터리에 나뉜 Flyway SQL의 버전 접두사 중복을 검사한다."""
from pathlib import Path
import unittest

MIGRATIONS = Path(__file__).resolve().parents[2] / 'storage/db/src/main/resources/db/migration'


class MigrationVersionTests(unittest.TestCase):
    def test_version_prefixes_are_unique_across_domains(self):
        """앱 초기화 전에 서로 다른 도메인 SQL의 같은 버전 번호를 찾아낸다."""
        files = sorted(MIGRATIONS.rglob('V*__*.sql'))
        self.assertTrue(files, '검사할 마이그레이션 SQL이 없습니다.')
        versions = {}
        duplicates = []
        for file in files:
            version = file.name.split('__', 1)[0]
            relative = str(file.relative_to(MIGRATIONS))
            if version in versions:
                duplicates.append(f'{version}: {versions[version]} / {relative}')
            else:
                versions[version] = relative
        self.assertFalse(duplicates, '\n'.join(duplicates))


if __name__ == '__main__':
    unittest.main()
